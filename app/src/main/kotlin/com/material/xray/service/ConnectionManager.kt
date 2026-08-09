package com.material.xray.service

import android.os.ParcelFileDescriptor
import com.material.xray.R
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XraySysStats
import com.material.xray.core.xray.parseXrayApiEndpoint
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

internal class ConnectionManager(
    private val configGenerator: ConfigGenerator,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
    dependencies: ConnectionManagerDependencies,
) : XrayHealthProbe {
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

    private val rootStrategy: XrayRuntimeStrategy = RootXrayRuntimeStrategy(
        processSupervisor = processSupervisor,
        rootRuntime = rootRuntime,
        cleanup = cleanup,
        xrayBinary = xrayBinary,
    )
    private val vpnServiceStrategy: XrayRuntimeStrategy = VpnServiceXrayRuntimeStrategy(
        processSupervisor = userProcessSupervisor,
        stateStore = stateStore,
        xrayBinary = xrayBinary,
    )

    @Volatile private var xrayStatsClient: ConnectionStatsClient? = null

    @Volatile private var xrayRoutingClient: ConnectionRoutingClient? = null

    private val xrayApiMutex = Mutex()

    private val xrayApiCloseGate = Any()

    private var xrayApiShutdownRequested = false

    @Volatile private var runtimeState: XrayRuntimeState = XrayRuntimeState.Inactive

    // Root is the only runtime that installs routing outside the process; the rootless runtime
    // gets it from Android's VpnService.
    val isUsingRootRuntime: Boolean
        get() = runtimeState.strategy?.managesSystemRouting == true

    suspend fun connect(
        server: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        vpnInterface: ParcelFileDescriptor? = null,
        transitionState: ConnectionState = ConnectionState.Connecting,
        preparation: ConnectionPreparation = ConnectionPreparation.Full,
    ) {
        stateCoordinator.startConnection(transitionState)
        val connectStartedAt = environment.elapsedRealtime()
        val fwmark = runtimeSettings.fwmark
        val routeTable = runtimeSettings.routeTable
        val routeMark = routeTable
        val bypassTable = routeTable + 1
        log.clear(LogSource.XRAY)
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")
        val strategy = strategyFor(useRootService = runtimeSettings.useRootService)
        val managesSystemRouting = strategy.managesSystemRouting
        runtimeState = XrayRuntimeState.Starting(strategy)

        try {
            val tunName = prepareRuntime(
                strategy = strategy,
                vpnInterface = vpnInterface,
                preparation = preparation,
                configuredTunName = runtimeSettings.tunName,
            ) ?: return
            val effectiveRuntimeSettings = runtimeSettings.copy(tunName = tunName)
            if (prepareXrayBinary(strategy, preparation) == null) return
            prepareRoutingData(preparation, transitionState)

            val physicalRouteResult = detectPhysicalRoute(managesSystemRouting, tunName)
            if (!physicalRouteResult.success) return

            val xrayServer = resolveServer(server, runtimeSettings.allowIpv6) ?: return

            val appRoutingPlan = appRoutingPlanner.build(
                baseTunName = tunName,
                baseRouteTable = routeTable,
                includeProxyRoutes = managesSystemRouting,
                includeTunRoutes = managesSystemRouting,
                defaultProxyServer = xrayServer,
                allowIpv6 = runtimeSettings.allowIpv6,
            )
            if (hasConfiguredAppRouting(appRoutingPlan)) {
                logAppRoutingPlan(appRoutingPlan)
            }

            val xrayApiEndpoint = strategy.nextApiEndpoint(environment)
            if (!prepareXrayApiAccess(xrayApiEndpoint)) return
            replaceXrayApiClients(xrayApiEndpoint)

            writeXrayConfig(
                xrayServer,
                effectiveRuntimeSettings,
                managesSystemRouting,
                appRoutingPlan,
                physicalRouteResult.route,
                xrayApiEndpoint,
            )
            val pid = startXrayProcess(strategy, vpnInterface)

            if (pid <= 0) {
                fail(environment.localizedString(R.string.connection_error_missing_process_id))
                return
            }
            runtimeState = XrayRuntimeState.Active(
                strategy = strategy,
                pid = pid,
                tunName = tunName,
                apiEndpoint = xrayApiEndpoint,
                physicalRoute = physicalRouteResult.route,
            )
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
                xrayApiEndpoint = xrayApiEndpoint,
            )

            if (
                !finishRuntimeSetup(
                    managesSystemRouting,
                    tunName,
                    fwmark,
                    routeTable,
                    bypassTable,
                    physicalRouteResult.route,
                    runtimeSettings.allowIpv6,
                    appRoutingPlan,
                    pid,
                )
            ) {
                return
            }

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
                ipRulesApplied = managesSystemRouting,
                connectStartedAt = connectStartedAt,
                xrayApiEndpoint = xrayApiEndpoint,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cleanCancelledConnectionAttempt() }
            throw error
        } catch (error: IOException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: SecurityException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: IllegalStateException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: SerializationException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        }
    }

    private fun hasConfiguredAppRouting(plan: AppRoutingPlan): Boolean = plan.proxyRoutes.isNotEmpty() ||
        plan.directUids.isNotEmpty()

    private suspend fun prepareRuntime(
        strategy: XrayRuntimeStrategy,
        vpnInterface: ParcelFileDescriptor?,
        preparation: ConnectionPreparation,
        configuredTunName: String,
    ): String? {
        val customTunName = configuredTunName.trim()
        if (preparation.cleansPreviousState && strategy.managesSystemRouting) {
            log.append(LogSource.APP, "Cleaning up previous state...")
            val cleaned = timedStep("Cleanup") {
                cleanup.ensureCleanState(fallbackTunName = customTunName.ifEmpty { LEGACY_DEFAULT_TUN_NAME })
            }
            if (!cleaned) {
                fail(environment.localizedString(R.string.connection_error_cleanup_failed), cleanState = false)
                return null
            }
        }

        val ready = if (strategy.managesSystemRouting) {
            prepareRootRuntime(preparation)
        } else {
            prepareVpnServiceRuntime(vpnInterface)
        }
        if (!ready) return null

        val tunName = if (strategy.managesSystemRouting && customTunName.isEmpty()) {
            timedStep("TUN interface name detection") {
                tunGateway.findAvailableWlanName()
            }?.also { selectedName ->
                log.append(LogSource.APP, "Selected available TUN interface name $selectedName")
            } ?: run {
                fail(
                    environment.localizedString(R.string.connection_error_tun_name_detection),
                    cleanState = false,
                )
                return null
            }
        } else {
            customTunName
        }

        strategy.prepareLogFile()
        return tunName
    }

    private suspend fun prepareRootRuntime(preparation: ConnectionPreparation): Boolean {
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
        if (preparation.reusesStaticRuntime) {
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

    private suspend fun prepareRootApiAccess(endpoint: XrayApiEndpoint): Boolean {
        if (endpoint !is XrayApiEndpoint.LoopbackTcp) return true
        if (rootRuntime.protectLoopbackApi(endpoint.port, environment.appUid)) return true
        fail(environment.localizedString(R.string.connection_error_secure_xray_api))
        return false
    }

    private suspend fun prepareXrayApiAccess(endpoint: XrayApiEndpoint): Boolean = if (endpoint is XrayApiEndpoint.LoopbackTcp) {
        timedStep("Xray API firewall setup") { prepareRootApiAccess(endpoint) }
    } else {
        prepareRootApiAccess(endpoint)
    }

    private suspend fun cleanOrphanedVpnServiceRuntime() {
        val staleState = stateStore.read()
            ?.takeIf { it.physicalInterface == VPN_SERVICE_INTERFACE_LABEL }
            ?: return
        userProcessSupervisor.stopOrphan(staleState.xrayPid)
        stateStore.delete()
    }

    private suspend fun prepareXrayBinary(strategy: XrayRuntimeStrategy, preparation: ConnectionPreparation): String? {
        val verifyAvailable = !preparation.reusesStaticRuntime
        if (verifyAvailable) {
            log.append(LogSource.APP, "Extracting xray binary...")
        } else {
            log.append(LogSource.APP, "xray binary extraction skipped for fast reconnect")
        }
        val activeBinaryPath = if (verifyAvailable) {
            timedStep("xray binary setup") { strategy.prepareBinary(verifyAvailable = true) }
        } else {
            strategy.prepareBinary(verifyAvailable = false)
        }
        if (activeBinaryPath == null) {
            fail(environment.localizedString(R.string.connection_error_xray_binary_not_found))
            return null
        }
        log.append(LogSource.APP, "xray binary ready at $activeBinaryPath")
        return activeBinaryPath
    }

    private suspend fun prepareRoutingData(preparation: ConnectionPreparation, transitionState: ConnectionState) {
        if (preparation.reusesStaticRuntime) {
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

    private suspend fun detectPhysicalRoute(managesSystemRouting: Boolean, tunName: String): PhysicalRouteResult {
        if (!managesSystemRouting) return PhysicalRouteResult(success = true, route = null)

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
        val resolvedServer = timedStep("Server address resolution") {
            serverResolver.resolve(server, allowIpv6)
        }
        if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
            val unresolvedHost = resolvedServer.unresolvedHosts.firstOrNull() ?: server.address
            fail(environment.localizedString(R.string.connection_error_server_address_unresolved, unresolvedHost))
            return null
        }
        if (resolvedServer.server.bootstrapDnsHosts.isNotEmpty()) {
            log.append(
                LogSource.APP,
                "Resolved ${resolvedServer.server.bootstrapDnsHosts.size} raw config endpoint hostname(s) " +
                    "(${resolvedServer.candidates.size} candidates)",
            )
        } else if (resolvedServer.selectedAddress != null) {
            log.append(
                LogSource.APP,
                "Resolved ${server.address} to ${resolvedServer.selectedAddress} (${resolvedServer.candidates.size} candidates)",
            )
        }
        return resolvedServer.server
    }

    private suspend fun writeXrayConfig(
        xrayServer: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        managesSystemRouting: Boolean,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        xrayApiEndpoint: XrayApiEndpoint,
    ) {
        val configJson = timedStep("Config generation") {
            withContext(Dispatchers.Default) {
                configGenerator.generate(
                    server = xrayServer,
                    tunName = runtimeSettings.tunName,
                    fwmark = runtimeSettings.fwmark.takeIf { managesSystemRouting } ?: 0,
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
                    xrayApiEndpoint = xrayApiEndpoint,
                    xrayBufferSizeKiB = runtimeSettings.xrayBufferSizeKiB,
                    tunMtu = runtimeSettings.tunMtu,
                )
            }
        }
        timedStep("Config write") {
            xrayBinary.writeConfig(configJson)
        }
        log.append(LogSource.APP, "Config written to ${xrayBinary.configPath()} (${configJson.length} chars)")
    }

    private fun logAppRoutingPlan(appRoutingPlan: AppRoutingPlan) {
        log.append(
            LogSource.APP,
            "App routing: ${appRoutingPlan.proxyRoutes.sumOf { route ->
                appRoutingPlan.tunRoutes.firstOrNull { it.tunName == route.tunName }?.uids?.size ?: 0
            }} apps assigned to ${appRoutingPlan.proxyRoutes.size} proxy route(s), ${appRoutingPlan.directUids.size} apps direct",
        )
    }

    private suspend fun startXrayProcess(strategy: XrayRuntimeStrategy, vpnInterface: ParcelFileDescriptor?): Int {
        log.append(LogSource.APP, "Starting xray process...")
        return timedStep("xray process launch") {
            strategy.startProcess(binDir = environment.binDir, vpnInterface = vpnInterface)
        }
    }

    private suspend fun writeConnectionStateFile(
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
        xrayApiEndpoint: XrayApiEndpoint,
    ) {
        stateStore.write(
            XrayState(
                xrayPid = pid,
                xrayApiPort = (xrayApiEndpoint as? XrayApiEndpoint.LoopbackTcp)?.port,
                tunName = tunName,
                serverName = serverName,
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

    private suspend fun waitForRootTun(managesSystemRouting: Boolean, tunName: String, allowIpv6: Boolean, pid: Int): Boolean {
        if (!managesSystemRouting) return true

        log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
        val tunSetup = timedStep("TUN setup") {
            tunGateway.configureTun(
                tunName = tunName,
                addressCidr = TunManager.DEFAULT_TUN_ADDRESS_CIDR,
                ipv6AddressCidr = TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR.takeIf { allowIpv6 },
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
        managesSystemRouting: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        allowIpv6: Boolean,
        appRoutingPlan: AppRoutingPlan,
        pid: Int,
    ): Boolean {
        if (
            !waitForRootTun(
                managesSystemRouting = managesSystemRouting,
                tunName = tunName,
                allowIpv6 = allowIpv6,
                pid = pid,
            )
        ) {
            return false
        }
        if (!waitForAppTuns(appRoutingPlan = appRoutingPlan, allowIpv6 = allowIpv6, pid = pid)) return false
        if (
            !applyRootRouting(
                managesSystemRouting,
                tunName,
                fwmark,
                routeTable,
                bypassTable,
                physicalRoute,
                allowIpv6,
                appRoutingPlan,
            )
        ) {
            return false
        }
        return waitForXrayApiReady(pid)
    }

    private suspend fun waitForAppTuns(appRoutingPlan: AppRoutingPlan, allowIpv6: Boolean, pid: Int): Boolean {
        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            log.append(LogSource.APP, "Waiting for app TUN interface '${route.tunName}'...")
            val appTunSetup = timedStep("App TUN setup ${index + 1}") {
                tunGateway.configureTun(
                    tunName = route.tunName,
                    addressCidr = TunManager.appTunAddressCidr(index + 1),
                    ipv6AddressCidr = TunManager.appTunIpv6AddressCidr(index + 1).takeIf { allowIpv6 },
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
            fail(environment.localizedString(R.string.connection_error_xray_crashed, readCrashReason()))
        } else {
            fail(
                tunSetup.error
                    ?: environment.localizedString(R.string.connection_error_tun_timeout, tunName),
            )
        }
    }

    private suspend fun applyRootRouting(
        managesSystemRouting: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        allowIpv6: Boolean,
        appRoutingPlan: AppRoutingPlan,
    ): Boolean {
        if (!managesSystemRouting) return true

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
                allowIpv6 = allowIpv6,
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

    private suspend fun waitForXrayApiReady(pid: Int): Boolean {
        val deadline = environment.elapsedRealtime() + XRAY_API_READY_TIMEOUT_MS
        do {
            if (!isProcessAlive(pid)) {
                fail(environment.localizedString(R.string.connection_error_xray_crashed, readCrashReason()))
                return false
            }
            if (readXraySysStats() != null) return true
            val remainingMs = deadline - environment.elapsedRealtime()
            if (remainingMs <= 0) break
            delay(minOf(XRAY_API_READY_RETRY_DELAY_MS, remainingMs))
        } while (true)

        fail(environment.localizedString(R.string.connection_error_xray_api_not_ready))
        return false
    }

    private suspend fun finishSuccessfulConnection(
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
        xrayApiEndpoint: XrayApiEndpoint,
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
            xrayApiEndpoint = xrayApiEndpoint,
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
        tunName = connectedState.tunName,
        fwmark = runtimeSettings.fwmark,
        routeTable = runtimeSettings.routeTable,
        allowIpv6 = runtimeSettings.allowIpv6,
    )

    suspend fun updatePhysicalBypassRoute(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult = activeRouting.updatePhysicalBypassRoute(
        connectedState = connectedState,
        physicalRoute = physicalRoute,
        tunName = connectedState.tunName,
        fwmark = runtimeSettings.fwmark,
        routeTable = runtimeSettings.routeTable,
    )

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? {
        if (!rootRuntime.open()) return null
        return tunGateway.detectPhysicalRoute(tunName)
    }

    suspend fun restoreRootApiClients(): Boolean {
        // Validate the persisted state before touching the firewall: installing loopback rules
        // for a runtime that turns out to be rootless or unreadable would leave orphaned iptables
        // state behind with nothing to reattach to.
        val state = stateStore.read() ?: return false
        // A record left by the rootless runtime describes a core that died with its process, so
        // there is nothing here to reattach to.
        if (state.physicalInterface == VPN_SERVICE_INTERFACE_LABEL) return false
        val configuredEndpoint = xrayBinary.readConfig()?.let(::parseXrayApiEndpoint)
        val endpoint = when (configuredEndpoint) {
            is XrayApiEndpoint.LoopbackTcp -> configuredEndpoint
            is XrayApiEndpoint.UnixSocket -> return false
            null ->
                state.xrayApiPort
                    ?.takeIf { it in 1..65_535 }
                    ?.let { XrayApiEndpoint.LoopbackTcp(it) }
                    ?: return false
        }
        if (!rootRuntime.protectLoopbackApi(endpoint.port, environment.appUid)) return false
        runtimeState = XrayRuntimeState.Active(
            strategy = rootStrategy,
            pid = state.xrayPid,
            tunName = state.tunName,
            apiEndpoint = endpoint,
            physicalRoute = selectPersistedPhysicalRoute(state),
        )
        replaceXrayApiClients(endpoint)
        return true
    }

    suspend fun disconnect(): Boolean = disconnect(updateState = true, fastCleanup = true)

    suspend fun disconnect(updateState: Boolean, fastCleanup: Boolean = false): Boolean {
        if (updateState) {
            stateCoordinator.markDisconnecting()
            log.append(LogSource.APP, "Disconnecting...")
        }
        // With no runtime of our own and nothing recorded by an earlier one, there is nothing
        // installed to take back.
        val cleaned = (runtimeState.strategy ?: persistedRuntimeStrategy())
            ?.release(fastCleanup = fastCleanup)
            ?: true
        runtimeState = XrayRuntimeState.Inactive
        closeXrayApiClients()
        if (!cleaned) {
            stateCoordinator.markError(environment.localizedString(R.string.connection_error_cleanup_failed))
            return false
        }
        if (updateState) {
            log.append(LogSource.APP, "Disconnected")
            stateCoordinator.markDisconnected()
        }
        return true
    }

    fun prepareForServiceDestruction() {
        runtimeState.strategy?.requestStop()
        requestXrayApiClientClose()
    }

    suspend fun ensureCleanRootRuntime(): Boolean {
        val cleaned = cleanup.ensureCleanState()
        runtimeState = XrayRuntimeState.Inactive
        if (!cleaned) stateCoordinator.markError(environment.localizedString(R.string.connection_error_cleanup_failed))
        return cleaned
    }

    private suspend fun fail(
        message: String,
        cleanState: Boolean = true,
        retryable: Boolean = true,
    ) {
        log.append(LogSource.APP, "ERROR: $message")
        var finalMessage = message
        if (cleanState) {
            if (!releaseStartedRuntime()) {
                finalMessage = environment.localizedString(R.string.connection_error_cleanup_failed)
                log.append(LogSource.APP, "ERROR: $finalMessage")
            }
            runtimeState = XrayRuntimeState.Inactive
        }
        closeXrayApiClients()
        stateCoordinator.markError(finalMessage, retryable)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanCancelledConnectionAttempt() {
        val cleanupErrors = mutableListOf<Exception>()
        try {
            if (!releaseStartedRuntime()) {
                log.append(LogSource.APP, "ERROR: Could not clean up cancelled Xray startup")
            }
        } catch (error: Exception) {
            cleanupErrors += error
        } finally {
            runtimeState = XrayRuntimeState.Inactive
            try {
                closeXrayApiClients()
            } catch (error: Exception) {
                cleanupErrors += error
            }
        }
        cleanupErrors.forEach { error ->
            log.append(LogSource.APP, "ERROR: Could not clean up cancelled Xray startup: ${error.message}")
        }
    }

    override suspend fun isProcessAlive(pid: Int): Boolean = processFor(pid)?.isAlive(pid) ?: false

    suspend fun isRestorableRootProcessAlive(pid: Int): Boolean = processSupervisor.isAlive(pid)

    suspend fun killProcess(pid: Int, signal: Int = 15): Boolean = processFor(pid)?.kill(pid, signal) ?: false

    override suspend fun readProcessResidentMemoryMb(pid: Int): Long? = processFor(pid)?.readResidentMemoryMb(pid)

    suspend fun readActiveConnectionCount(pid: Int): Int? = processFor(pid)?.readActiveConnectionCount(pid)

    suspend fun readProcessMetrics(pid: Int): ProcessMetrics? = processFor(pid)?.readProcessMetrics(pid)

    suspend fun readOutboundTrafficStatsBytes(): Map<String, Long> = withXrayApiClients {
        xrayStatsClient?.queryOutboundTrafficStatsBytes().orEmpty()
    }

    override suspend fun readXraySysStats(): XraySysStats? = withXrayApiClients { xrayStatsClient?.getSysStats() }

    override suspend fun readCrashReason(): String = runCatching {
        runtimeState.strategy?.readCrashReason()
    }.getOrNull() ?: "xray process exited"

    internal suspend fun readBalancerSelection(balancerTag: String) = withXrayApiClients {
        xrayRoutingClient?.queryBalancerSelection(balancerTag)
    }

    private suspend fun replaceXrayApiClients(endpoint: XrayApiEndpoint) = withXrayApiClients {
        replaceXrayApiClientsLocked(endpoint)
    }

    private fun replaceXrayApiClientsLocked(endpoint: XrayApiEndpoint) {
        closeXrayApiClientsLocked()
        apiClientFactory.create(endpoint).also { clients ->
            xrayStatsClient = clients.stats
            xrayRoutingClient = clients.routing
        }
    }

    private suspend fun closeXrayApiClients() = withXrayApiClients {
        closeXrayApiClientsLocked()
    }

    private fun closeXrayApiClientsLocked() {
        xrayStatsClient?.close()
        xrayStatsClient = null
        xrayRoutingClient?.close()
        xrayRoutingClient = null
    }

    private suspend fun <T> withXrayApiClients(block: suspend () -> T): T {
        xrayApiMutex.lock()
        try {
            return block()
        } finally {
            synchronized(xrayApiCloseGate) {
                try {
                    if (xrayApiShutdownRequested) {
                        closeXrayApiClientsLocked()
                    }
                } finally {
                    xrayApiMutex.unlock()
                }
            }
        }
    }

    private fun requestXrayApiClientClose() {
        synchronized(xrayApiCloseGate) {
            xrayApiShutdownRequested = true
            if (!xrayApiMutex.tryLock()) return
            try {
                closeXrayApiClientsLocked()
            } finally {
                xrayApiMutex.unlock()
            }
        }
    }

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = environment.appUid
        return if (appUid > 0) directUids + appUid else directUids
    }

    private fun strategyFor(useRootService: Boolean): XrayRuntimeStrategy = if (useRootService) {
        rootStrategy
    } else {
        vpnServiceStrategy
    }

    /** Resolves the core that [pid] belongs to, or null when this manager did not start it. */
    private fun processFor(pid: Int): XrayRuntimeProcess? = (runtimeState as? XrayRuntimeState.Active)
        ?.takeIf { it.pid == pid }
        ?.strategy

    private suspend fun persistedRuntimeStrategy(): XrayRuntimeStrategy? = stateStore.read()?.let { state ->
        strategyFor(useRootService = state.physicalInterface != VPN_SERVICE_INTERFACE_LABEL)
    }

    /**
     * Releases the runtime a connection attempt had already started.
     *
     * The root fallback covers teardown after the runtime was already reset, which happens when a
     * failure is itself cancelled part-way through; root cleanup is the safe choice there because
     * it is the only runtime that can leave routing behind.
     */
    private suspend fun releaseStartedRuntime(): Boolean = (runtimeState.strategy ?: rootStrategy)
        .release(fastCleanup = false)

    private fun selectPersistedPhysicalRoute(state: XrayState): TunManager.PhysicalRoute? = state.physicalInterface
        ?.takeIf { it.isNotBlank() && it != VPN_SERVICE_INTERFACE_LABEL }
        ?.let { physicalInterface ->
            TunManager.PhysicalRoute(
                dev = physicalInterface,
                gateway = state.physicalGateway,
                table = state.physicalTable,
            )
        }

    private suspend fun <T> timedStep(label: String, block: suspend () -> T): T {
        val startedAt = environment.elapsedRealtime()
        return try {
            block()
        } finally {
            log.append(LogSource.APP, "$label took ${environment.elapsedRealtime() - startedAt} ms")
        }
    }
}

private sealed interface XrayRuntimeState {
    val strategy: XrayRuntimeStrategy?

    data object Inactive : XrayRuntimeState {
        override val strategy: XrayRuntimeStrategy? = null
    }

    data class Starting(
        override val strategy: XrayRuntimeStrategy,
    ) : XrayRuntimeState

    data class Active(
        override val strategy: XrayRuntimeStrategy,
        val pid: Int,
        val tunName: String,
        val apiEndpoint: XrayApiEndpoint,
        val physicalRoute: TunManager.PhysicalRoute?,
    ) : XrayRuntimeState
}

private const val LEGACY_DEFAULT_TUN_NAME = "xray0"
private const val XRAY_API_READY_TIMEOUT_MS = 10_000L
private const val XRAY_API_READY_RETRY_DELAY_MS = 250L
