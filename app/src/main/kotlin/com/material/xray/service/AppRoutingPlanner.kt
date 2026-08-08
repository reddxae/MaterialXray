package com.material.xray.service

import com.material.xray.core.app.AppInventorySource
import com.material.xray.core.app.appKey
import com.material.xray.core.app.profileIdForUid
import com.material.xray.core.xray.AppProxyRoute
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.TunManager
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.ServerConfig

internal data class AppRoutingPlan(
    val directUids: Set<Int>,
    val proxyRoutes: List<AppProxyRoute>,
    val tunRoutes: List<TunManager.AppTunRoute>,
    val proxyServerIds: List<Long>,
    val routeProfileIds: Set<Int>,
)

internal interface RoutingPlanBuilder {
    suspend fun build(
        baseTunName: String,
        baseRouteTable: Int,
        includeProxyRoutes: Boolean,
        includeTunRoutes: Boolean = true,
        defaultProxyServer: ServerConfig? = null,
        allowIpv6: Boolean = false,
    ): AppRoutingPlan
}

internal class AppRoutingPlanner(
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val appInventory: AppInventorySource,
    private val serverAddressResolver: ServerAddressResolver,
    private val log: LogBuffer,
) : RoutingPlanBuilder {
    override suspend fun build(
        baseTunName: String,
        baseRouteTable: Int,
        includeProxyRoutes: Boolean,
        includeTunRoutes: Boolean,
        defaultProxyServer: ServerConfig?,
        allowIpv6: Boolean,
    ): AppRoutingPlan {
        val appSnapshot = appInventory.loadRoutingSnapshot()
        val assignments = appBypassDao.getAll()
        val installedAppsByKey = appSnapshot.apps.associateBy { it.appKey }
        val assignmentsWithUid = assignments.mapNotNull { assignment ->
            val currentUid = installedAppsByKey[appKey(assignment.profileId, assignment.packageName)]?.uid
            val uid = currentUid?.takeIf { it > 0 } ?: assignment.uid
            if (uid > 0) RoutedAppAssignment(assignment.routeAssignment(), uid) else null
        }
        val assignmentUids = assignmentsWithUid
            .map { it.uid }
            .filter { it > 0 }
            .toSet()
        val routeProfileIds = (appSnapshot.profileIds + assignmentUids.map(::profileIdForUid)).ifEmpty { setOf(0) }

        val directUids = assignmentsWithUid.directUids()

        if (!includeTunRoutes) {
            return emptyPlan(directUids, routeProfileIds)
        }

        val defaultProxyUids = assignmentsWithUid
            .filter { it.route.mode == AppRouteMode.DefaultSelected }
            .map { it.uid }
            .filter { it > 0 }
            .toSet() + defaultSelectedUidsForUnassignedApps(appSnapshot.apps.map { it.uid }, assignmentUids)

        val proxyAssignments = assignmentsWithUid.proxyAssignments()

        if (defaultProxyUids.isEmpty() && proxyAssignments.isEmpty()) {
            return emptyPlan(directUids, routeProfileIds)
        }

        return buildProxyRoutingPlan(
            directUids = directUids,
            routeProfileIds = routeProfileIds,
            baseTunName = baseTunName,
            baseRouteTable = baseRouteTable,
            includeProxyRoutes = includeProxyRoutes,
            defaultProxyServer = defaultProxyServer,
            defaultProxyUids = defaultProxyUids,
            proxyAssignments = proxyAssignments,
            allowIpv6 = allowIpv6,
        )
    }

    private suspend fun buildProxyRoutingPlan(
        directUids: Set<Int>,
        routeProfileIds: Set<Int>,
        baseTunName: String,
        baseRouteTable: Int,
        includeProxyRoutes: Boolean,
        defaultProxyServer: ServerConfig?,
        defaultProxyUids: Set<Int>,
        proxyAssignments: Map<Long, List<RoutedAppAssignment>>,
        allowIpv6: Boolean,
    ): AppRoutingPlan {
        val routeBuilder = AppProxyRouteBuilder(baseTunName, baseRouteTable)

        if (defaultProxyUids.isNotEmpty()) {
            addDefaultProxyRoute(routeBuilder, defaultProxyUids, includeProxyRoutes, defaultProxyServer)
        }

        var routeCapReached = false
        for ((serverId, assignments) in proxyAssignments) {
            if (routeBuilder.tunRouteCount >= MAX_APP_PROXY_ROUTES) {
                routeCapReached = true
                break
            }
            addServerProxyRoute(routeBuilder, serverId, assignments, includeProxyRoutes, allowIpv6)
        }
        if (routeCapReached) {
            log.append(
                LogSource.APP,
                "Only the first $MAX_APP_PROXY_ROUTES app proxy server groups can be active at once; extra groups are ignored",
            )
        }

        return AppRoutingPlan(
            directUids = directUids,
            proxyRoutes = routeBuilder.proxyRoutes,
            tunRoutes = routeBuilder.tunRoutes,
            proxyServerIds = routeBuilder.proxyServerIds,
            routeProfileIds = routeProfileIds,
        )
    }

    private fun addDefaultProxyRoute(
        routeBuilder: AppProxyRouteBuilder,
        defaultProxyUids: Set<Int>,
        includeProxyRoutes: Boolean,
        defaultProxyServer: ServerConfig?,
    ) {
        val routeTunName = routeBuilder.addTunRoute(DEFAULT_SELECTED_CONFIG_ROUTE_ID, defaultProxyUids)
        if (!includeProxyRoutes) return

        val activeServer = defaultProxyServer
        if (activeServer == null) {
            log.append(LogSource.APP, "Skipping default selected config app route: active server is not ready")
            routeBuilder.removeLastTunRoute()
            return
        }

        routeBuilder.proxyRoutes += AppProxyRoute(
            inboundTag = DEFAULT_SELECTED_CONFIG_INBOUND_TAG,
            tunName = routeTunName,
            outboundTag = DEFAULT_SELECTED_CONFIG_OUTBOUND_TAG,
            server = activeServer,
            applyRoutingRules = true,
        )
    }

    private suspend fun addServerProxyRoute(
        routeBuilder: AppProxyRouteBuilder,
        serverId: Long,
        assignments: List<RoutedAppAssignment>,
        includeProxyRoutes: Boolean,
        allowIpv6: Boolean,
    ) {
        val uids = assignments.map { it.uid }.filter { it > 0 }.toSet()
        if (uids.isEmpty()) return
        val routeTunName = routeBuilder.addTunRoute(serverId, uids)

        if (!includeProxyRoutes) return

        val serverEntity = serverRepository.getById(serverId)
        if (serverEntity == null) {
            log.append(LogSource.APP, "Skipping app route for missing server id=$serverId")
            routeBuilder.removeLastTunRoute()
            return
        }

        val parsedServerResult = runCatching { serverRepository.parseConfig(serverEntity) }
        if (parsedServerResult.isFailure) {
            log.append(
                LogSource.APP,
                "Skipping app route for ${serverEntity.name}: ${parsedServerResult.exceptionOrNull()?.message}",
            )
            routeBuilder.removeLastTunRoute()
            return
        }

        routeBuilder.proxyRoutes += buildServerProxyRoute(
            serverId = serverId,
            routeTunName = routeTunName,
            parsedServer = parsedServerResult.getOrThrow(),
            allowIpv6 = allowIpv6,
        )
    }

    private suspend fun buildServerProxyRoute(
        serverId: Long,
        routeTunName: String,
        parsedServer: ServerConfig,
        allowIpv6: Boolean,
    ): AppProxyRoute {
        val resolvedServer = serverAddressResolver.resolve(parsedServer, allowIpv6)
        if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
            val unresolvedHost = resolvedServer.unresolvedHosts.firstOrNull() ?: parsedServer.address
            error("Could not resolve $unresolvedHost for app route ${parsedServer.name}")
        }
        val routedServer = resolvedServer.server

        return AppProxyRoute(
            inboundTag = "app-in-$serverId",
            tunName = routeTunName,
            outboundTag = "app-proxy-$serverId",
            server = routedServer,
        )
    }

    private fun emptyPlan(directUids: Set<Int>, routeProfileIds: Set<Int>): AppRoutingPlan = AppRoutingPlan(
        directUids = directUids,
        proxyRoutes = emptyList(),
        tunRoutes = emptyList(),
        proxyServerIds = emptyList(),
        routeProfileIds = routeProfileIds,
    )

    private fun List<RoutedAppAssignment>.directUids(): Set<Int> = filter {
        it.route.mode == AppRouteMode.Direct || it.route.mode == AppRouteMode.Bypass
    }
        .map { it.uid }
        .filter { it > 0 }
        .toSet()

    private fun List<RoutedAppAssignment>.proxyAssignments(): Map<Long, List<RoutedAppAssignment>> = filter {
        it.uid > 0 && it.route.mode == AppRouteMode.Server && it.route.serverId != null
    }
        .groupBy { requireNotNull(it.route.serverId) }
        .toSortedMap()

    private class AppProxyRouteBuilder(
        private val baseTunName: String,
        private val baseRouteTable: Int,
    ) {
        val proxyRoutes = mutableListOf<AppProxyRoute>()
        val tunRoutes = mutableListOf<TunManager.AppTunRoute>()
        val proxyServerIds = mutableListOf<Long>()

        val tunRouteCount: Int get() = tunRoutes.size

        fun removeLastTunRoute() {
            proxyServerIds.removeLastItem()
            tunRoutes.removeLastItem()
        }

        fun addTunRoute(routeKey: Long, uids: Set<Int>): String {
            val routeIndex = tunRoutes.size + 1
            val routeTunName = TunManager.appTunName(baseTunName, routeIndex)
            proxyServerIds += routeKey
            tunRoutes += TunManager.AppTunRoute(
                tunName = routeTunName,
                routeTable = TunManager.appRouteTable(baseRouteTable, routeIndex),
                uids = uids,
            )
            return routeTunName
        }

        private fun <T> MutableList<T>.removeLastItem() {
            if (isNotEmpty()) removeAt(lastIndex)
        }
    }

    private fun defaultSelectedUidsForUnassignedApps(
        installedUids: List<Int>,
        assignmentUids: Set<Int>,
    ): Set<Int> = installedUids
        .asSequence()
        .filter { it > 0 && it !in assignmentUids }
        .toSet()

    private data class RoutedAppAssignment(
        val route: AppRouteAssignment,
        val uid: Int,
    )

    companion object {
        private const val MAX_APP_PROXY_ROUTES = 64
        private const val DEFAULT_SELECTED_CONFIG_ROUTE_ID = Long.MIN_VALUE
        private const val DEFAULT_SELECTED_CONFIG_INBOUND_TAG = "app-in-default-selected"
        private const val DEFAULT_SELECTED_CONFIG_OUTBOUND_TAG = "proxy"
    }
}
