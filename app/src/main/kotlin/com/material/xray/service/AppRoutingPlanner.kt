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
    ): AppRoutingPlan {
        val assignments = appBypassDao.getAll()
        val appSnapshot = appInventory.loadRoutingSnapshot()
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

        val directUids = assignmentsWithUid
            .filter { it.route.mode == AppRouteMode.Direct || it.route.mode == AppRouteMode.Bypass }
            .map { it.uid }
            .filter { it > 0 }
            .toSet()

        if (!includeTunRoutes) {
            return AppRoutingPlan(
                directUids = directUids,
                proxyRoutes = emptyList(),
                tunRoutes = emptyList(),
                proxyServerIds = emptyList(),
                routeProfileIds = routeProfileIds,
            )
        }

        val defaultProxyUids = assignmentsWithUid
            .filter { it.route.mode == AppRouteMode.DefaultSelected }
            .map { it.uid }
            .filter { it > 0 }
            .toSet() + defaultSelectedUidsForUnassignedApps(appSnapshot.apps.map { it.uid }, assignmentUids)

        val proxyAssignments = assignmentsWithUid
            .filter { it.uid > 0 && it.route.mode == AppRouteMode.Server && it.route.serverId != null }
            .groupBy { requireNotNull(it.route.serverId) }
            .toSortedMap()

        if (defaultProxyUids.isEmpty() && proxyAssignments.isEmpty()) {
            return AppRoutingPlan(
                directUids = directUids,
                proxyRoutes = emptyList(),
                tunRoutes = emptyList(),
                proxyServerIds = emptyList(),
                routeProfileIds = routeProfileIds,
            )
        }

        val routeGroupCount = proxyAssignments.size + if (defaultProxyUids.isNotEmpty()) 1 else 0
        if (routeGroupCount > MAX_APP_PROXY_ROUTES) {
            log.append(
                LogSource.APP,
                "Only the first $MAX_APP_PROXY_ROUTES app proxy server groups can be active at once; extra groups are ignored",
            )
        }

        val proxyRoutes = mutableListOf<AppProxyRoute>()
        val tunRoutes = mutableListOf<TunManager.AppTunRoute>()
        val proxyServerIds = mutableListOf<Long>()

        fun <T> MutableList<T>.removeLastItem() {
            if (isNotEmpty()) removeAt(lastIndex)
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

        if (defaultProxyUids.isNotEmpty()) {
            val routeTunName = addTunRoute(DEFAULT_SELECTED_CONFIG_ROUTE_ID, defaultProxyUids)
            if (includeProxyRoutes) {
                val activeServer = defaultProxyServer
                if (activeServer == null) {
                    log.append(LogSource.APP, "Skipping default selected config app route: active server is not ready")
                    proxyServerIds.removeLastItem()
                    tunRoutes.removeLastItem()
                } else {
                    proxyRoutes += AppProxyRoute(
                        inboundTag = DEFAULT_SELECTED_CONFIG_INBOUND_TAG,
                        tunName = routeTunName,
                        outboundTag = DEFAULT_SELECTED_CONFIG_OUTBOUND_TAG,
                        server = activeServer,
                        applyRoutingRules = true,
                    )
                }
            }
        }

        proxyAssignments.entries.take(MAX_APP_PROXY_ROUTES - tunRoutes.size).forEach { (serverId, assignments) ->
            val uids = assignments.map { it.uid }.filter { it > 0 }.toSet()
            if (uids.isEmpty()) return@forEach
            val routeTunName = addTunRoute(serverId, uids)

            if (!includeProxyRoutes) {
                return@forEach
            }

            val serverEntity = serverRepository.getById(serverId)
            if (serverEntity == null) {
                log.append(LogSource.APP, "Skipping app route for missing server id=$serverId")
                proxyServerIds.removeLastItem()
                tunRoutes.removeLastItem()
                return@forEach
            }

            val parsedServerResult = runCatching { serverRepository.parseConfig(serverEntity) }
            if (parsedServerResult.isFailure) {
                log.append(
                    LogSource.APP,
                    "Skipping app route for ${serverEntity.name}: ${parsedServerResult.exceptionOrNull()?.message}",
                )
                proxyServerIds.removeLastItem()
                tunRoutes.removeLastItem()
                return@forEach
            }
            val parsedServer = parsedServerResult.getOrThrow()

            val routedServer = if (parsedServer.rawConfigJson.isNotBlank()) {
                log.append(LogSource.APP, "Using raw outbound from ${parsedServer.name} for app routing")
                parsedServer
            } else {
                val resolvedServer = serverAddressResolver.resolve(parsedServer)
                if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
                    error("Could not resolve ${parsedServer.address} for app route ${parsedServer.name}")
                }
                resolvedServer.server
            }

            val inboundTag = "app-in-$serverId"
            val outboundTag = "app-proxy-$serverId"
            proxyRoutes += AppProxyRoute(
                inboundTag = inboundTag,
                tunName = routeTunName,
                outboundTag = outboundTag,
                server = routedServer,
            )
        }

        return AppRoutingPlan(
            directUids = directUids,
            proxyRoutes = proxyRoutes,
            tunRoutes = tunRoutes,
            proxyServerIds = proxyServerIds,
            routeProfileIds = routeProfileIds,
        )
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
