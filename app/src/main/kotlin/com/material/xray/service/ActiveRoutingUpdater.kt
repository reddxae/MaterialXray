package com.material.xray.service

import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.XrayState
import com.material.xray.model.ConnectionState

sealed interface PhysicalRouteUpdateResult {
    data class Applied(val route: TunManager.PhysicalRoute) : PhysicalRouteUpdateResult
    data object RouteUnavailable : PhysicalRouteUpdateResult
    data object RequiresReconnect : PhysicalRouteUpdateResult
}

internal interface ActiveRoutingStateStore {
    fun read(): XrayState?
    fun write(state: XrayState)
}

internal interface TunRoutingGateway {
    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute?

    suspend fun configureTun(
        tunName: String,
        addressCidr: String,
        isProcessAlive: suspend () -> Boolean,
    ): TunManager.TunSetupResult

    suspend fun applyRouting(
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute,
        bypassUids: Set<Int>,
        appTunRoutes: List<TunManager.AppTunRoute>,
        managedAppRouteCount: Int,
        routeProfileIds: Set<Int>,
    ): TunManager.RoutingResult
}

internal class StateFileRoutingStateStore(
    private val stateFile: StateFile,
) : ActiveRoutingStateStore {
    override fun read(): XrayState? = stateFile.read()

    override fun write(state: XrayState) {
        stateFile.write(state)
    }
}

internal class TunManagerRoutingGateway(
    private val tunManager: TunManager,
) : TunRoutingGateway {
    override suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? =
        tunManager.detectPhysicalRoute(tunName)

    override suspend fun configureTun(
        tunName: String,
        addressCidr: String,
        isProcessAlive: suspend () -> Boolean,
    ): TunManager.TunSetupResult =
        tunManager.configureTun(tunName, addressCidr, isProcessAlive)

    override suspend fun applyRouting(
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute,
        bypassUids: Set<Int>,
        appTunRoutes: List<TunManager.AppTunRoute>,
        managedAppRouteCount: Int,
        routeProfileIds: Set<Int>,
    ): TunManager.RoutingResult =
        tunManager.applyRouting(
            tunName = tunName,
            fwmark = fwmark,
            routeTable = routeTable,
            bypassTable = bypassTable,
            physicalRoute = physicalRoute,
            bypassUids = bypassUids,
            appTunRoutes = appTunRoutes,
            managedAppRouteCount = managedAppRouteCount,
            routeProfileIds = routeProfileIds,
        )
}

internal class ActiveRoutingUpdater(
    private val appUidProvider: () -> Int,
    private val tunGateway: TunRoutingGateway,
    private val stateStore: ActiveRoutingStateStore,
    private val routingPlanBuilder: RoutingPlanBuilder,
    private val processProbe: XrayProcessProbe,
    private val log: LogBuffer,
    private val elapsedRealtime: () -> Long,
) {
    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
    ): Boolean {
        val startedAt = elapsedRealtime()
        val persistedState = stateStore.read()
        if (persistedState == null) {
            log.append(LogSource.APP, "Fast app routing update skipped: active state file is missing")
            return false
        }
        if (persistedState.tunName != tunName || persistedState.routeTable != routeTable || persistedState.fwmark != fwmark) {
            log.append(LogSource.APP, "Fast app routing update skipped: active routing settings changed")
            return false
        }
        if (!processProbe.isAlive(connectedState.corePid)) {
            log.append(LogSource.APP, "Fast app routing update skipped: xray process is not running")
            return false
        }

        val appRoutingPlan = try {
            routingPlanBuilder.build(tunName, routeTable, includeProxyRoutes = false)
        } catch (error: Exception) {
            log.append(LogSource.APP, "Fast app routing update skipped: ${error.message ?: "could not build app routing plan"}")
            return false
        }

        if (appRoutingPlan.proxyServerIds != persistedState.appProxyServerIds) {
            log.append(
                LogSource.APP,
                "Fast app routing update skipped: app proxy routes changed " +
                    "(${persistedState.appProxyServerIds.size} -> ${appRoutingPlan.proxyServerIds.size})",
            )
            return false
        }

        val physicalRoute = timedStep("Physical route detection") {
            tunGateway.detectPhysicalRoute(tunName)
        }
        if (physicalRoute == null) {
            log.append(LogSource.APP, "Fast app routing update skipped: could not detect physical network route")
            return false
        }

        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            val appTunSetup = timedStep("App TUN check ${index + 1}") {
                tunGateway.configureTun(
                    tunName = route.tunName,
                    addressCidr = TunManager.appTunAddressCidr(index + 1),
                ) { processProbe.isAlive(connectedState.corePid) }
            }
            if (!appTunSetup.success) {
                log.append(LogSource.APP, "Fast app routing update skipped: ${appTunSetup.error ?: "app TUN ${route.tunName} is unavailable"}")
                return false
            }
        }

        val bypassTable = routeTable + 1
        val routingResult = timedStep("IP routing update") {
            tunGateway.applyRouting(
                tunName = tunName,
                fwmark = fwmark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                physicalRoute = physicalRoute,
                bypassUids = runtimeBypassUids(appRoutingPlan.directUids),
                appTunRoutes = appRoutingPlan.tunRoutes,
                managedAppRouteCount = persistedState.appProxyServerIds.size,
                routeProfileIds = appRoutingPlan.routeProfileIds,
            )
        }
        if (!routingResult.success) {
            log.append(LogSource.APP, "Fast app routing update skipped: ${routingResult.error ?: "unknown routing error"}")
            return false
        }

        stateStore.write(
            persistedState.copy(
                ipRulesApplied = true,
                appProxyServerIds = appRoutingPlan.proxyServerIds,
                physicalInterface = physicalRoute.dev,
                physicalGateway = physicalRoute.gateway,
                physicalTable = physicalRoute.table,
            )
        )
        log.append(LogSource.APP, "App routing changes applied in ${elapsedRealtime() - startedAt} ms")
        return true
    }

    suspend fun reapplyPhysicalRoutingForNetworkChange(
        connectedState: ConnectionState.Connected,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
    ): PhysicalRouteUpdateResult {
        val startedAt = elapsedRealtime()
        val persistedState = stateStore.read()
        if (persistedState == null) {
            log.append(LogSource.APP, "Physical routing refresh requires reconnect: active state file is missing")
            return PhysicalRouteUpdateResult.RequiresReconnect
        }
        if (persistedState.tunName != tunName || persistedState.routeTable != routeTable || persistedState.fwmark != fwmark) {
            log.append(LogSource.APP, "Physical routing refresh requires reconnect: active routing settings changed")
            return PhysicalRouteUpdateResult.RequiresReconnect
        }
        if (!processProbe.isAlive(connectedState.corePid)) {
            log.append(LogSource.APP, "Physical routing refresh requires reconnect: xray process is not running")
            return PhysicalRouteUpdateResult.RequiresReconnect
        }

        val appRoutingPlan = try {
            routingPlanBuilder.build(tunName, routeTable, includeProxyRoutes = false)
        } catch (error: Exception) {
            log.append(LogSource.APP, "Physical routing refresh requires reconnect: ${error.message ?: "could not build app routing plan"}")
            return PhysicalRouteUpdateResult.RequiresReconnect
        }

        if (appRoutingPlan.proxyServerIds != persistedState.appProxyServerIds) {
            log.append(LogSource.APP, "Physical routing refresh requires reconnect: app proxy routes changed")
            return PhysicalRouteUpdateResult.RequiresReconnect
        }

        val physicalRoute = timedStep("Physical route detection") {
            tunGateway.detectPhysicalRoute(tunName)
        } ?: return PhysicalRouteUpdateResult.RouteUnavailable

        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            val appTunSetup = timedStep("App TUN check ${index + 1}") {
                tunGateway.configureTun(
                    tunName = route.tunName,
                    addressCidr = TunManager.appTunAddressCidr(index + 1),
                ) { processProbe.isAlive(connectedState.corePid) }
            }
            if (!appTunSetup.success) {
                log.append(
                    LogSource.APP,
                    "Physical routing refresh requires reconnect: ${appTunSetup.error ?: "app TUN ${route.tunName} is unavailable"}",
                )
                return PhysicalRouteUpdateResult.RequiresReconnect
            }
        }

        val bypassTable = routeTable + 1
        val routingResult = timedStep("IP routing refresh") {
            tunGateway.applyRouting(
                tunName = tunName,
                fwmark = fwmark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                physicalRoute = physicalRoute,
                bypassUids = runtimeBypassUids(appRoutingPlan.directUids),
                appTunRoutes = appRoutingPlan.tunRoutes,
                managedAppRouteCount = persistedState.appProxyServerIds.size,
                routeProfileIds = appRoutingPlan.routeProfileIds,
            )
        }
        if (!routingResult.success) {
            log.append(LogSource.APP, "Physical routing refresh requires reconnect: ${routingResult.error ?: "unknown routing error"}")
            return PhysicalRouteUpdateResult.RequiresReconnect
        }

        stateStore.write(
            persistedState.copy(
                ipRulesApplied = true,
                appProxyServerIds = appRoutingPlan.proxyServerIds,
                physicalInterface = physicalRoute.dev,
                physicalGateway = physicalRoute.gateway,
                physicalTable = physicalRoute.table,
            )
        )
        log.append(LogSource.APP, "Physical routing refreshed in ${elapsedRealtime() - startedAt} ms")
        return PhysicalRouteUpdateResult.Applied(physicalRoute)
    }

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = appUidProvider()
        return if (appUid > 0) directUids + appUid else directUids
    }

    private suspend fun <T> timedStep(label: String, block: suspend () -> T): T {
        val startedAt = elapsedRealtime()
        return try {
            block()
        } finally {
            log.append(LogSource.APP, "$label took ${elapsedRealtime() - startedAt} ms")
        }
    }
}
