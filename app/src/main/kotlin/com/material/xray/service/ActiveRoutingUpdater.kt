package com.material.xray.service

import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayState
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

sealed interface PhysicalRouteUpdateResult {
    data class Applied(val route: TunManager.PhysicalRoute) : PhysicalRouteUpdateResult
    data object RequiresReconnect : PhysicalRouteUpdateResult
}

internal interface ActiveRoutingStateStore {
    suspend fun read(): XrayState?
    suspend fun write(state: XrayState)
}

internal interface ConnectionStateStore : ActiveRoutingStateStore {
    suspend fun delete()
}

internal interface ActiveRoutingController {
    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        allowIpv6: Boolean,
    ): Boolean

    suspend fun updatePhysicalBypassRoute(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
    ): PhysicalRouteUpdateResult
}

internal interface TunRoutingGateway {
    suspend fun findAvailableWlanName(): String?

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute?

    suspend fun configureTun(
        tunName: String,
        addressCidr: String,
        ipv6AddressCidr: String?,
        isProcessAlive: suspend () -> Boolean,
    ): TunManager.TunSetupResult

    suspend fun applyRouting(
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute,
        allowIpv6: Boolean,
        bypassUids: Set<Int>,
        appTunRoutes: List<TunManager.AppTunRoute>,
        managedAppRouteCount: Int,
        routeProfileIds: Set<Int>,
    ): TunManager.RoutingResult

    suspend fun replacePhysicalBypassRoute(
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute,
    ): TunManager.RoutingResult
}

internal class StateFileRoutingStateStore(
    private val stateFile: StateFile,
) : ConnectionStateStore {
    override suspend fun read(): XrayState? = withContext(Dispatchers.IO) { stateFile.read() }

    override suspend fun write(state: XrayState) {
        withContext(Dispatchers.IO) { stateFile.write(state) }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) { stateFile.delete() }
    }
}

internal class TunManagerRoutingGateway(
    private val tunManager: TunManager,
) : TunRoutingGateway {
    override suspend fun findAvailableWlanName(): String? = tunManager.findAvailableWlanName()

    override suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? = tunManager.detectPhysicalRoute(tunName)

    override suspend fun configureTun(
        tunName: String,
        addressCidr: String,
        ipv6AddressCidr: String?,
        isProcessAlive: suspend () -> Boolean,
    ): TunManager.TunSetupResult = tunManager.configureTun(
        tunName = tunName,
        addressCidr = addressCidr,
        ipv6AddressCidr = ipv6AddressCidr,
        isProcessAlive = isProcessAlive,
    )

    override suspend fun applyRouting(
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute,
        allowIpv6: Boolean,
        bypassUids: Set<Int>,
        appTunRoutes: List<TunManager.AppTunRoute>,
        managedAppRouteCount: Int,
        routeProfileIds: Set<Int>,
    ): TunManager.RoutingResult = tunManager.applyRouting(
        tunName = tunName,
        fwmark = fwmark,
        routeTable = routeTable,
        bypassTable = bypassTable,
        physicalRoute = physicalRoute,
        allowIpv6 = allowIpv6,
        bypassUids = bypassUids,
        appTunRoutes = appTunRoutes,
        managedAppRouteCount = managedAppRouteCount,
        routeProfileIds = routeProfileIds,
    )

    override suspend fun replacePhysicalBypassRoute(
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute,
    ): TunManager.RoutingResult = tunManager.replacePhysicalBypassRoute(bypassTable, physicalRoute)
}

internal class ActiveRoutingUpdater(
    private val appUidProvider: () -> Int,
    private val tunGateway: TunRoutingGateway,
    private val stateStore: ActiveRoutingStateStore,
    private val routingPlanBuilder: RoutingPlanBuilder,
    private val processProbe: XrayProcessProbe,
    private val log: LogBuffer,
    private val elapsedRealtime: () -> Long,
    onProgressStarted: (ConnectionProgress) -> Long = { 0L },
    onProgressFinished: (Long) -> Unit = {},
) : ActiveRoutingController {
    private val stepExecutor = ConnectionStepExecutor(
        elapsedRealtime = elapsedRealtime,
        log = { message -> log.append(LogSource.APP, message) },
        onProgressStarted = onProgressStarted,
        onProgressFinished = onProgressFinished,
    )

    override suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        allowIpv6: Boolean,
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

        val appRoutingPlan = buildAppRoutingPlan(
            tunName = tunName,
            routeTable = routeTable,
            failurePrefix = "Fast app routing update skipped",
        ) ?: return false

        if (appRoutingPlan.proxyServerIds != persistedState.appProxyServerIds) {
            log.append(
                LogSource.APP,
                "Fast app routing update skipped: app proxy routes changed " +
                    "(${persistedState.appProxyServerIds.size} -> ${appRoutingPlan.proxyServerIds.size})",
            )
            return false
        }

        val physicalRoute = stepExecutor.execute(
            ConnectionStep(
                "Fast physical route detection",
                progress = null,
                isSuccessful = { it != null },
                reported = false,
                action = { tunGateway.detectPhysicalRoute(tunName) },
            ),
        )
        if (physicalRoute == null) {
            log.append(LogSource.APP, "Fast app routing update skipped: could not detect physical network route")
            return false
        }

        val mainTunSetup = stepExecutor.execute(
            ConnectionStep(
                "Main TUN check",
                ConnectionProgress.ConfiguringTunnel,
                isSuccessful = { it.success },
                action = {
                    tunGateway.configureTun(
                        tunName = tunName,
                        addressCidr = TunManager.DEFAULT_TUN_ADDRESS_CIDR,
                        ipv6AddressCidr = TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR.takeIf { allowIpv6 },
                    ) { processProbe.isAlive(connectedState.corePid) }
                },
            ),
        )
        if (!mainTunSetup.success) {
            log.append(LogSource.APP, "Fast app routing update skipped: ${mainTunSetup.error ?: "main TUN $tunName is unavailable"}")
            return false
        }

        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            val appTunSetup = stepExecutor.execute(
                ConnectionStep(
                    "App TUN check ${index + 1}",
                    ConnectionProgress.ConfiguringTunnel,
                    isSuccessful = { it.success },
                    action = {
                        tunGateway.configureTun(
                            tunName = route.tunName,
                            addressCidr = TunManager.appTunAddressCidr(index + 1),
                            ipv6AddressCidr = TunManager.appTunIpv6AddressCidr(index + 1).takeIf { allowIpv6 },
                        ) { processProbe.isAlive(connectedState.corePid) }
                    },
                ),
            )
            if (!appTunSetup.success) {
                log.append(LogSource.APP, "Fast app routing update skipped: ${appTunSetup.error ?: "app TUN ${route.tunName} is unavailable"}")
                return false
            }
        }

        val bypassTable = routeTable + 1
        val routingResult = stepExecutor.execute(
            ConnectionStep(
                "IP routing update",
                ConnectionProgress.UpdatingAppRouting,
                isSuccessful = { it.success },
                action = {
                    tunGateway.applyRouting(
                        tunName = tunName,
                        fwmark = fwmark,
                        routeTable = routeTable,
                        bypassTable = bypassTable,
                        physicalRoute = physicalRoute,
                        allowIpv6 = allowIpv6,
                        bypassUids = runtimeBypassUids(appRoutingPlan.directUids),
                        appTunRoutes = appRoutingPlan.tunRoutes,
                        managedAppRouteCount = persistedState.appProxyServerIds.size,
                        routeProfileIds = appRoutingPlan.routeProfileIds,
                    )
                },
            ),
        )
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
            ),
        )
        log.append(LogSource.APP, "App routing changes applied in ${elapsedRealtime() - startedAt} ms")
        return true
    }

    override suspend fun updatePhysicalBypassRoute(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
    ): PhysicalRouteUpdateResult {
        val startedAt = elapsedRealtime()
        val persistedState = stateStore.read() ?: return PhysicalRouteUpdateResult.RequiresReconnect
        val routingIdentityChanged = persistedState.tunName != tunName ||
            persistedState.routeTable != routeTable ||
            persistedState.fwmark != fwmark
        if (!persistedState.ipRulesApplied || routingIdentityChanged) {
            return PhysicalRouteUpdateResult.RequiresReconnect
        }
        if (physicalRoute.dev != connectedState.physicalInterface) {
            return PhysicalRouteUpdateResult.RequiresReconnect
        }
        if (!processProbe.isAlive(connectedState.corePid)) {
            return PhysicalRouteUpdateResult.RequiresReconnect
        }

        val routingResult = stepExecutor.execute(
            ConnectionStep(
                "Physical bypass route update",
                ConnectionProgress.UpdatingNetworkRoute,
                isSuccessful = { it.success },
                action = {
                    tunGateway.replacePhysicalBypassRoute(
                        bypassTable = routeTable + 1,
                        physicalRoute = physicalRoute,
                    )
                },
            ),
        )
        if (!routingResult.success) {
            log.append(
                LogSource.APP,
                "Physical bypass route update requires reconnect: ${routingResult.error ?: "unknown routing error"}",
            )
            return PhysicalRouteUpdateResult.RequiresReconnect
        }

        stateStore.write(
            persistedState.copy(
                physicalInterface = physicalRoute.dev,
                physicalGateway = physicalRoute.gateway,
                physicalTable = physicalRoute.table,
            ),
        )
        log.append(LogSource.APP, "Physical bypass route updated in ${elapsedRealtime() - startedAt} ms")
        return PhysicalRouteUpdateResult.Applied(physicalRoute)
    }

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = appUidProvider()
        return if (appUid > 0) directUids + appUid else directUids
    }

    private suspend fun buildAppRoutingPlan(
        tunName: String,
        routeTable: Int,
        failurePrefix: String,
    ): AppRoutingPlan? = try {
        routingPlanBuilder.build(tunName, routeTable, includeProxyRoutes = false)
    } catch (error: IllegalArgumentException) {
        logRoutingPlanFailure(failurePrefix, error)
        null
    } catch (error: IllegalStateException) {
        logRoutingPlanFailure(failurePrefix, error)
        null
    } catch (error: SerializationException) {
        logRoutingPlanFailure(failurePrefix, error)
        null
    }

    private fun logRoutingPlanFailure(prefix: String, error: Throwable) {
        log.append(LogSource.APP, "$prefix: ${error.message ?: "could not build app routing plan"}")
    }
}
