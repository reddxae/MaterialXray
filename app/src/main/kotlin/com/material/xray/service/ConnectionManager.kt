package com.material.xray.service

import android.os.ParcelFileDescriptor
import com.material.xray.R
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XRAY_API_SOCKET_NAME_PREFIX
import com.material.xray.core.xray.XrayState
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

internal class ConnectionManager(
    private val configGenerator: ConfigGenerator,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
    dependencies: ConnectionManagerDependencies,
    private val onXrayLogReady: () -> Unit = {},
) {
    private val environment = dependencies.environment
    private val rootRuntime = dependencies.rootRuntime
    private val xrayBinary = dependencies.xrayBinary
    private val routingData = dependencies.routingData
    private val serverResolver = dependencies.serverResolver
    private val tunGateway = dependencies.tunGateway
    private val cleanup = dependencies.cleanup
    private val stateStore = dependencies.stateStore
    private val processSupervisor = dependencies.rootProcess
    private val userProcessSupervisor = dependencies.userProcess
    private val diagnostics = dependencies.diagnostics
    private val appRoutingPlanner = dependencies.routingPlanBuilder
    private val activeRouting = dependencies.activeRouting
    private val apiClientFactory = dependencies.apiClientFactory

    @Volatile private var xrayStatsClient: ConnectionStatsClient? = null

    @Volatile private var xrayRoutingClient: ConnectionRoutingClient? = null
    private var runningViaVpnService = false

    suspend fun connect(
        server: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        vpnInterface: ParcelFileDescriptor? = null,
        transitionState: ConnectionState = ConnectionState.Connecting,
        cleanStateFirst: Boolean = true,
        fastReconnect: Boolean = false,
    ) {
        stateCoordinator.startConnection(transitionState)
        val connectStartedAt = environment.elapsedRealtime()
        val tunName = runtimeSettings.tunName
        val fwmark = runtimeSettings.fwmark
        val routeTable = runtimeSettings.routeTable
        val routeMark = routeTable
        val bypassTable = routeTable + 1
        log.clear()
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")
        val useRootService = runtimeSettings.useRootService
        runningViaVpnService = !useRootService

        try {
            if (!prepareRuntime(useRootService, vpnInterface, cleanStateFirst, fastReconnect, tunName)) return
            if (prepareXrayBinary(useRootService, fastReconnect) == null) return
            prepareRoutingData(fastReconnect, transitionState)

            val physicalRouteResult = detectPhysicalRoute(useRootService, tunName)
            if (!physicalRouteResult.success) return

            val xrayServer = resolveServer(server, runtimeSettings.allowIpv6) ?: return

            val appRoutingPlan = appRoutingPlanner.build(
                baseTunName = tunName,
                baseRouteTable = routeTable,
                includeProxyRoutes = useRootService,
                includeTunRoutes = useRootService,
                defaultProxyServer = xrayServer,
                allowIpv6 = runtimeSettings.allowIpv6,
            )
            if (appRoutingPlan.proxyRoutes.isNotEmpty() || appRoutingPlan.directUids.isNotEmpty()) {
                logAppRoutingPlan(appRoutingPlan)
            }

            val xrayApiSocketName = nextXrayApiSocketName()
            closeXrayApiClients()
            apiClientFactory.create(xrayApiSocketName).also { clients ->
                xrayStatsClient = clients.stats
                xrayRoutingClient = clients.routing
            }

            writeXrayConfig(xrayServer, runtimeSettings, useRootService, appRoutingPlan, physicalRouteResult.route, xrayApiSocketName)
            val pid = startXrayProcess(useRootService, vpnInterface)

            if (pid <= 0) {
                fail(environment.localizedString(R.string.connection_error_missing_process_id))
                return
            }

            log.append(LogSource.APP, "xray running with PID $pid")
            writeConnectionStateFile(
                pid = pid,
                tunName = tunName,
                serverName = server.name,
                fwmark = fwmark,
                routeMark = routeMark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                appRoutingPlan = appRoutingPlan,
                physicalRoute = physicalRouteResult.route,
                ipRulesApplied = false,
            )

            if (!finishRuntimeSetup(useRootService, tunName, fwmark, routeTable, bypassTable, physicalRouteResult.route, appRoutingPlan, pid)) return

            finishSuccessfulConnection(
                server = server,
                pid = pid,
                tunName = tunName,
                fwmark = fwmark,
                routeMark = routeMark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                appRoutingPlan = appRoutingPlan,
                physicalRoute = physicalRouteResult.route,
                ipRulesApplied = useRootService,
                connectStartedAt = connectStartedAt,
            )
        } catch (e: IOException) {
            fail(e.message ?: environment.localizedString(R.string.error_unknown))
        } catch (e: SecurityException) {
            fail(e.message ?: environment.localizedString(R.string.error_unknown))
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: environment.localizedString(R.string.error_unknown))
        } catch (e: IllegalStateException) {
            fail(e.message ?: environment.localizedString(R.string.error_unknown))
        } catch (e: SerializationException) {
            fail(e.message ?: environment.localizedString(R.string.error_unknown))
        }
    }

    private suspend fun prepareRuntime(
        useRootService: Boolean,
        vpnInterface: ParcelFileDescriptor?,
        cleanStateFirst: Boolean,
        fastReconnect: Boolean,
        tunName: String,
    ): Boolean {
        if (cleanStateFirst && useRootService) {
            log.append(LogSource.APP, "Cleaning up previous state...")
            timedStep("Cleanup") {
                cleanup.ensureCleanState(fallbackTunName = tunName)
            }
        }

        val ready = if (useRootService) {
            prepareRootRuntime(fastReconnect)
        } else {
            prepareVpnServiceRuntime(vpnInterface)
        }
        if (!ready) return false

        if (useRootService) {
            processSupervisor.prepareLogFile()
        } else {
            userProcessSupervisor.prepareLogFile()
        }
        onXrayLogReady()
        return true
    }

    private suspend fun prepareRootRuntime(fastReconnect: Boolean): Boolean {
        log.append(LogSource.APP, "Requesting root access...")
        val rootGranted = timedStep("Root shell setup") {
            rootRuntime.open()
        }
        if (!rootGranted) {
            fail(environment.localizedString(R.string.connection_error_root_access_denied))
            return false
        }
        log.append(
            LogSource.APP,
            "Root access granted (namespace=${rootRuntime.networkNamespaceName()})",
        )
        if (fastReconnect) {
            log.append(LogSource.APP, "Runtime exemption check skipped for fast reconnect")
        } else {
            processSupervisor.ensureNativeRuntimeExemptions()
        }
        return true
    }

    private suspend fun prepareVpnServiceRuntime(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (vpnInterface == null) {
            fail(
                environment.localizedString(R.string.connection_error_vpn_permission_required),
                retryable = false,
            )
            return false
        }
        log.append(LogSource.APP, "Using Android VpnService")
        cleanOrphanedVpnServiceRuntime()
        userProcessSupervisor.stop()
        return true
    }

    private suspend fun cleanOrphanedVpnServiceRuntime() {
        val staleState = stateStore.read()
            ?.takeIf { it.physicalInterface == VPN_SERVICE_INTERFACE_LABEL }
            ?: return
        userProcessSupervisor.stopOrphan(staleState.xrayPid)
        stateStore.delete()
    }

    private suspend fun prepareXrayBinary(useRootService: Boolean, fastReconnect: Boolean): String? {
        if (fastReconnect) {
            log.append(LogSource.APP, "xray binary extraction skipped for fast reconnect")
        } else {
            log.append(LogSource.APP, "Extracting xray binary...")
            val xrayReady = timedStep("xray binary setup") {
                if (useRootService) {
                    xrayBinary.ensureRootBinaryExtracted()
                } else {
                    xrayBinary.ensureAndroidBinaryAvailable()
                }
            }
            if (!xrayReady) {
                fail(environment.localizedString(R.string.connection_error_xray_binary_not_found))
                return null
            }
        }

        val activeBinaryPath = if (useRootService) {
            xrayBinary.rootBinaryPath
        } else {
            requireNotNull(xrayBinary.androidBinaryPath)
        }
        log.append(LogSource.APP, "xray binary ready at $activeBinaryPath")
        return activeBinaryPath
    }

    private suspend fun prepareRoutingData(fastReconnect: Boolean, transitionState: ConnectionState) {
        if (fastReconnect) {
            log.append(LogSource.APP, "Routing data check skipped for fast reconnect")
            return
        }

        log.append(LogSource.APP, "Checking routing data...")
        if (routingData.needsRefresh()) {
            stateCoordinator.markUpdatingRoutingData()
            log.append(LogSource.APP, "Updating routing data...")
        }
        val geoDataStatus = timedStep("Routing data setup") {
            routingData.ensureReady()
        }
        stateCoordinator.startConnection(transitionState)
        if (geoDataStatus.downloaded) {
            log.append(
                LogSource.APP,
                "Routing data updated (geoip=${geoDataStatus.geoipUrl}, geosite=${geoDataStatus.geositeUrl})",
            )
        } else {
            log.append(LogSource.APP, "Routing data already up to date")
        }
    }

    private suspend fun detectPhysicalRoute(useRootService: Boolean, tunName: String): PhysicalRouteResult {
        if (!useRootService) return PhysicalRouteResult(success = true, route = null)

        val route = timedStep("Physical route detection") {
            tunGateway.detectPhysicalRoute(tunName)
        }
        if (route == null) {
            fail(environment.localizedString(R.string.connection_error_physical_route_not_found))
            return PhysicalRouteResult(success = false, route = null)
        }
        log.append(
            LogSource.APP,
            "Physical bypass route: dev=${route.dev}" +
                (route.gateway?.let { " via=$it" } ?: "") +
                (route.table?.let { " table=$it" } ?: ""),
        )
        return PhysicalRouteResult(success = true, route = route)
    }

    private suspend fun resolveServer(server: ServerConfig, allowIpv6: Boolean): ServerConfig? {
        val resolvedServer = if (server.rawConfigJson.isNotBlank()) {
            log.append(LogSource.APP, "Skipping endpoint pre-resolution for raw JSON subscription config")
            ServerResolution(
                server = server,
                attempted = false,
                selectedAddress = null,
                candidates = emptyList(),
            )
        } else {
            timedStep("Server address resolution") {
                serverResolver.resolve(server, allowIpv6)
            }
        }
        if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
            fail(environment.localizedString(R.string.connection_error_server_address_unresolved, server.address))
            return null
        }
        if (resolvedServer.selectedAddress != null) {
            log.append(
                LogSource.APP,
                "Resolved ${server.address} to ${resolvedServer.selectedAddress} (${resolvedServer.candidates.size} candidates)",
            )
        }
        return resolvedServer.server
    }

    private fun writeXrayConfig(
        xrayServer: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        useRootService: Boolean,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        xrayApiSocketName: String,
    ) {
        val configJson = configGenerator.generate(
            server = xrayServer,
            tunName = runtimeSettings.tunName,
            fwmark = runtimeSettings.fwmark.takeIf { useRootService } ?: 0,
            dnsServers = runtimeSettings.dnsServers,
            domesticDnsServers = runtimeSettings.domesticDnsServers,
            logLevel = runtimeSettings.logLevel,
            defaultOutbound = runtimeSettings.defaultOutbound,
            bypassLan = runtimeSettings.bypassLan,
            allowIpv6 = runtimeSettings.allowIpv6,
            routingRules = runtimeSettings.routingRules,
            routingDomainStrategy = runtimeSettings.routingDomainStrategy,
            routingDomainMatcher = runtimeSettings.routingDomainMatcher,
            routingFallbackOutbound = runtimeSettings.routingFallbackOutbound,
            appProxyRoutes = appRoutingPlan.proxyRoutes,
            physicalInterface = physicalRoute?.dev,
            xrayApiSocketName = xrayApiSocketName,
            xrayBufferSizeKiB = runtimeSettings.xrayBufferSizeKiB,
            tunMtu = runtimeSettings.tunMtu,
        )
        xrayBinary.writeConfig(configJson)
        log.append(LogSource.APP, "Config written to ${xrayBinary.configPath()}")
    }

    private fun logAppRoutingPlan(appRoutingPlan: AppRoutingPlan) {
        log.append(
            LogSource.APP,
            "App routing: ${appRoutingPlan.proxyRoutes.sumOf { route ->
                appRoutingPlan.tunRoutes.firstOrNull { it.tunName == route.tunName }?.uids?.size ?: 0
            }} apps assigned to ${appRoutingPlan.proxyRoutes.size} proxy route(s), ${appRoutingPlan.directUids.size} apps direct",
        )
    }

    private suspend fun startXrayProcess(useRootService: Boolean, vpnInterface: ParcelFileDescriptor?): Int {
        log.append(LogSource.APP, "Starting xray process...")
        val binDir = environment.binDir
        return timedStep("xray process launch") {
            if (useRootService) {
                processSupervisor.start(binDir)
            } else {
                userProcessSupervisor.start(
                    binDir = binDir,
                    tunFd = requireNotNull(vpnInterface).fd,
                )
            }
        }
    }

    private fun writeConnectionStateFile(
        pid: Int,
        tunName: String,
        serverName: String,
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        bypassTable: Int,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        ipRulesApplied: Boolean,
    ) {
        stateStore.write(
            XrayState(
                xrayPid = pid,
                tunName = tunName,
                serverName = serverName,
                nftTableCreated = false,
                ipRulesApplied = ipRulesApplied,
                fwmark = fwmark,
                routeMark = routeMark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                appProxyServerIds = appRoutingPlan.proxyServerIds,
                physicalInterface = physicalRoute?.dev ?: VPN_SERVICE_INTERFACE_LABEL,
                physicalGateway = physicalRoute?.gateway,
                physicalTable = physicalRoute?.table,
            ),
        )
    }

    private suspend fun waitForRootTun(useRootService: Boolean, tunName: String, pid: Int): Boolean {
        if (!useRootService) return true

        log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
        val tunSetup = timedStep("TUN setup") {
            tunGateway.configureTun(
                tunName = tunName,
                addressCidr = TunManager.DEFAULT_TUN_ADDRESS_CIDR,
            ) { isProcessAlive(pid) }
        }
        if (!tunSetup.success) {
            handleTunSetupFailure(tunSetup, tunName, pid, diagnosticsStage = "tun")
            return false
        }
        log.append(LogSource.APP, "TUN interface $tunName is up")
        return true
    }

    private suspend fun finishRuntimeSetup(
        useRootService: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        appRoutingPlan: AppRoutingPlan,
        pid: Int,
    ): Boolean {
        if (!waitForRootTun(useRootService, tunName, pid)) return false
        if (!waitForAppTuns(appRoutingPlan, pid)) return false
        return applyRootRouting(useRootService, tunName, fwmark, routeTable, bypassTable, physicalRoute, appRoutingPlan)
    }

    private suspend fun waitForAppTuns(appRoutingPlan: AppRoutingPlan, pid: Int): Boolean {
        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            log.append(LogSource.APP, "Waiting for app TUN interface '${route.tunName}'...")
            val appTunSetup = timedStep("App TUN setup ${index + 1}") {
                tunGateway.configureTun(
                    tunName = route.tunName,
                    addressCidr = TunManager.appTunAddressCidr(index + 1),
                ) { isProcessAlive(pid) }
            }
            if (!appTunSetup.success) {
                handleTunSetupFailure(appTunSetup, route.tunName, pid, diagnosticsStage = "app-tun")
                return false
            }
            log.append(LogSource.APP, "App TUN interface ${route.tunName} is up")
        }
        return true
    }

    private suspend fun handleTunSetupFailure(
        tunSetup: TunManager.TunSetupResult,
        tunName: String,
        pid: Int,
        diagnosticsStage: String,
    ) {
        val stage = if (tunSetup.processExited) "$diagnosticsStage-exit" else "$diagnosticsStage-failure"
        diagnostics.logNamespaceDiagnostics(stage = stage, tunName = tunName, xrayPid = pid)
        if (tunSetup.processExited) {
            fail(environment.localizedString(R.string.connection_error_xray_crashed, processSupervisor.readCrashReason()))
        } else {
            fail(
                tunSetup.error
                    ?: environment.localizedString(R.string.connection_error_tun_timeout, tunName),
            )
        }
    }

    private suspend fun applyRootRouting(
        useRootService: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        appRoutingPlan: AppRoutingPlan,
    ): Boolean {
        if (!useRootService) return true

        val bypassUids = runtimeBypassUids(appRoutingPlan.directUids)
        log.append(
            LogSource.APP,
            "Applying IP routing (tunTable=$routeTable, bypassTable=$bypassTable, fwmark=$fwmark, ${bypassUids.size} apps direct, ${appRoutingPlan.tunRoutes.size} app proxy route(s))...",
        )
        val routingResult = timedStep("IP routing setup") {
            tunGateway.applyRouting(
                tunName = tunName,
                fwmark = fwmark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                physicalRoute = requireNotNull(physicalRoute),
                bypassUids = bypassUids,
                appTunRoutes = appRoutingPlan.tunRoutes,
                managedAppRouteCount = appRoutingPlan.tunRoutes.size,
                routeProfileIds = appRoutingPlan.routeProfileIds,
            )
        }
        if (!routingResult.success) {
            fail(
                environment.localizedString(
                    R.string.connection_error_apply_ip_routing,
                    routingResult.error ?: environment.localizedString(R.string.error_unknown),
                ),
            )
            return false
        }
        log.append(LogSource.APP, "IP routing applied")
        return true
    }

    private fun finishSuccessfulConnection(
        server: ServerConfig,
        pid: Int,
        tunName: String,
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        bypassTable: Int,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        ipRulesApplied: Boolean,
        connectStartedAt: Long,
    ) {
        writeConnectionStateFile(
            pid = pid,
            tunName = tunName,
            serverName = server.name,
            fwmark = fwmark,
            routeMark = routeMark,
            routeTable = routeTable,
            bypassTable = bypassTable,
            appRoutingPlan = appRoutingPlan,
            physicalRoute = physicalRoute,
            ipRulesApplied = ipRulesApplied,
        )

        log.append(LogSource.APP, "Connected to ${server.name}")
        log.append(
            LogSource.APP,
            "Connection setup finished in ${environment.elapsedRealtime() - connectStartedAt} ms",
        )
        stateCoordinator.markConnected(
            ConnectionState.Connected(
                serverName = server.name,
                corePid = pid,
                tunName = tunName,
                physicalInterface = physicalRoute?.dev ?: VPN_SERVICE_INTERFACE_LABEL,
                physicalGateway = physicalRoute?.gateway,
                physicalTable = physicalRoute?.table,
            ),
        )
    }

    private data class PhysicalRouteResult(
        val success: Boolean,
        val route: TunManager.PhysicalRoute?,
    )

    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean = activeRouting.applyAppRoutingChanges(
        connectedState = connectedState,
        tunName = runtimeSettings.tunName,
        fwmark = runtimeSettings.fwmark,
        routeTable = runtimeSettings.routeTable,
    )

    suspend fun reapplyPhysicalRoutingForNetworkChange(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult = activeRouting.reapplyPhysicalRoutingForNetworkChange(
        connectedState = connectedState,
        tunName = runtimeSettings.tunName,
        fwmark = runtimeSettings.fwmark,
        routeTable = runtimeSettings.routeTable,
    )

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? {
        if (!rootRuntime.open()) return null
        return tunGateway.detectPhysicalRoute(tunName)
    }

    suspend fun detectPhysicalInterface(tunName: String): String? = detectPhysicalRoute(tunName)?.dev

    suspend fun disconnect() {
        disconnect(updateState = true, fastRootCleanup = true)
    }

    suspend fun disconnect(updateState: Boolean, fastRootCleanup: Boolean = false) {
        if (updateState) {
            stateCoordinator.markDisconnecting()
            log.append(LogSource.APP, "Disconnecting...")
        }
        val wasRunningViaVpnService = runningViaVpnService
        userProcessSupervisor.stop()
        if (wasRunningViaVpnService) {
            stateStore.delete()
        } else {
            val hasRootState = stateStore.read() != null
            val knownStateStopped = if (fastRootCleanup) {
                cleanup.ensureKnownStateStopped()
            } else {
                false
            }
            if (!knownStateStopped && (hasRootState || updateState)) {
                cleanup.ensureCleanState()
            }
        }
        runningViaVpnService = false
        closeXrayApiClients()
        if (updateState) {
            log.append(LogSource.APP, "Disconnected")
            stateCoordinator.markDisconnected()
        }
    }

    fun prepareForServiceDestruction() {
        if (runningViaVpnService) userProcessSupervisor.requestStop()
        closeXrayApiClients()
    }

    suspend fun ensureCleanRootRuntime() {
        cleanup.ensureCleanState()
        runningViaVpnService = false
    }

    private suspend fun fail(
        message: String,
        cleanState: Boolean = true,
        retryable: Boolean = true,
    ) {
        log.append(LogSource.APP, "ERROR: $message")
        if (cleanState) {
            userProcessSupervisor.stop()
            if (!runningViaVpnService) {
                cleanup.ensureCleanState()
            } else {
                stateStore.delete()
            }
            runningViaVpnService = false
        }
        closeXrayApiClients()
        stateCoordinator.markError(message, retryable)
    }

    suspend fun isProcessAlive(pid: Int): Boolean = if (runningViaVpnService) {
        userProcessSupervisor.isAlive(pid)
    } else {
        processSupervisor.isAlive(pid)
    }

    suspend fun killProcess(pid: Int, signal: Int = 15): Boolean = if (runningViaVpnService) {
        userProcessSupervisor.kill(pid, signal)
    } else {
        processSupervisor.kill(pid, signal)
    }

    suspend fun readProcessResidentMemoryMb(pid: Int): Long? = if (runningViaVpnService) {
        userProcessSupervisor.readResidentMemoryMb(pid)
    } else {
        processSupervisor.readResidentMemoryMb(pid)
    }

    suspend fun readActiveConnectionCount(pid: Int): Int? = if (runningViaVpnService) {
        withContext(Dispatchers.IO) { userProcessSupervisor.readActiveConnectionCount(pid) }
    } else {
        rootRuntime.readActiveConnectionCount(pid)
    }

    suspend fun readOutboundTrafficStatsBytes(): Map<String, Long> = xrayStatsClient
        ?.queryOutboundTrafficStatsBytes()
        .orEmpty()

    internal suspend fun readBalancerSelection(balancerTag: String) = xrayRoutingClient?.queryBalancerSelection(balancerTag)

    private fun closeXrayApiClients() {
        xrayStatsClient?.close()
        xrayStatsClient = null
        xrayRoutingClient?.close()
        xrayRoutingClient = null
    }

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = environment.appUid
        return if (appUid > 0) directUids + appUid else directUids
    }

    private fun nextXrayApiSocketName(): String = "$XRAY_API_SOCKET_NAME_PREFIX-${environment.processId}-${environment.elapsedRealtime()}"

    private suspend fun <T> timedStep(label: String, block: suspend () -> T): T {
        val startedAt = environment.elapsedRealtime()
        return try {
            block()
        } finally {
            log.append(LogSource.APP, "$label took ${environment.elapsedRealtime() - startedAt} ms")
        }
    }
}

private const val VPN_SERVICE_INTERFACE_LABEL = "VpnService"
