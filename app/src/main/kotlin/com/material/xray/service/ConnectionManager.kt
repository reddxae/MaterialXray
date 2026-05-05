package com.material.xray.service

import android.content.Context
import android.os.SystemClock
import com.material.xray.core.app.AppInventory
import com.material.xray.core.network.CaptivePortalDetector
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.CleanupManager
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayState
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings

class ConnectionManager(
    private val context: Context,
    private val shell: RootShell,
    private val captivePortalDetector: CaptivePortalDetector,
    private val configGenerator: ConfigGenerator,
    private val geoDataManager: GeoDataManager,
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val appInventory: AppInventory,
    private val stateHolder: ConnectionStateHolder,
    private val log: LogBuffer,
    private val onXrayLogReady: () -> Unit = {},
) {
    private val xrayBinary = XrayBinary(context)
    private val serverAddressResolver = ServerAddressResolver()
    private val tunManager = TunManager(shell)
    private val cleanupManager = CleanupManager(context, shell)
    private val stateFile = StateFile(context)
    private val processSupervisor = XrayProcessSupervisor(
        environment = AndroidXrayRuntimeEnvironment(context),
        commandRunner = RootShellCommandRunner(shell),
        xrayBinary = XrayBinaryProcessBinary(xrayBinary),
        log = log,
    )
    private val diagnostics = ConnectionDiagnostics(RootShellDiagnosticCommandRunner(shell), log)
    private val appRoutingPlanner = AppRoutingPlanner(
        appBypassDao = appBypassDao,
        serverRepository = serverRepository,
        appInventory = appInventory,
        serverAddressResolver = serverAddressResolver,
        log = log,
    )
    private val activeRoutingUpdater = ActiveRoutingUpdater(
        appUidProvider = { context.applicationInfo.uid },
        tunGateway = TunManagerRoutingGateway(tunManager),
        stateStore = StateFileRoutingStateStore(stateFile),
        routingPlanBuilder = appRoutingPlanner,
        processProbe = processSupervisor,
        log = log,
        elapsedRealtime = SystemClock::elapsedRealtime,
    )

    suspend fun connect(
        server: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        transitionState: ConnectionState = ConnectionState.Connecting,
        cleanStateFirst: Boolean = true,
        fastReconnect: Boolean = false,
    ) {
        stateHolder.update(transitionState)
        val connectStartedAt = SystemClock.elapsedRealtime()
        val tunName = runtimeSettings.tunName
        val fwmark = runtimeSettings.fwmark
        val routeTable = runtimeSettings.routeTable
        val routeMark = routeTable
        val bypassTable = routeTable + 1
        log.clear()
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")

        try {
            if (cleanStateFirst) {
                log.append(LogSource.APP, "Cleaning up previous state...")
                timedStep("Cleanup") {
                    cleanupManager.ensureCleanState(fallbackTunName = tunName)
                }
            }

            if (fastReconnect) {
                log.append(LogSource.APP, "Captive portal check skipped for fast reconnect")
            } else {
                val captivePortalAccess = timedStep("Captive portal check") {
                    verifyCaptivePortalAccess()
                }
                if (!captivePortalAccess) return
            }

            log.append(LogSource.APP, "Requesting root access...")
            val rootGranted = timedStep("Root shell setup") {
                shell.open()
            }
            if (!rootGranted) {
                fail("Root access denied")
                return
            }
            log.append(
                LogSource.APP,
                "Root access granted (namespace=${shell.defaultNetworkNamespace().name.lowercase()})",
            )
            if (fastReconnect) {
                log.append(LogSource.APP, "Runtime exemption check skipped for fast reconnect")
            } else {
                processSupervisor.ensureNativeRuntimeExemptions()
            }

            processSupervisor.prepareLogFile()
            onXrayLogReady()

            if (fastReconnect) {
                log.append(LogSource.APP, "xray binary extraction skipped for fast reconnect")
            } else {
                log.append(LogSource.APP, "Extracting xray binary...")
                val xrayReady = timedStep("xray binary extraction") {
                    xrayBinary.ensureExtracted()
                }
                if (!xrayReady) {
                    fail("xray binary not found — check assets")
                    return
                }
            }
            log.append(LogSource.APP, "xray binary ready at ${xrayBinary.binaryPath}")

            if (fastReconnect) {
                log.append(LogSource.APP, "Routing data check skipped for fast reconnect")
            } else {
                log.append(LogSource.APP, "Checking routing data...")
                if (geoDataManager.needsRefresh()) {
                    stateHolder.update(ConnectionState.UpdatingRoutingData)
                    log.append(LogSource.APP, "Updating routing data...")
                }
                val geoDataStatus = timedStep("Routing data setup") {
                    geoDataManager.ensureReady()
                }
                stateHolder.update(transitionState)
                if (geoDataStatus.downloaded) {
                    log.append(
                        LogSource.APP,
                        "Routing data updated (geoip=${geoDataStatus.geoipUrl}, geosite=${geoDataStatus.geositeUrl})",
                    )
                } else {
                    log.append(LogSource.APP, "Routing data already up to date")
                }
            }

            val physicalRoute = timedStep("Physical route detection") {
                tunManager.detectPhysicalRoute(tunName)
            }
            if (physicalRoute == null) {
                fail("Could not detect physical network route for Xray bypass")
                return
            }
            log.append(
                LogSource.APP,
                "Physical bypass route: dev=${physicalRoute.dev}" +
                        (physicalRoute.gateway?.let { " via=$it" } ?: "") +
                        (physicalRoute.table?.let { " table=$it" } ?: ""),
            )

            val resolvedServer = if (server.rawConfigJson.isNotBlank()) {
                log.append(LogSource.APP, "Skipping endpoint pre-resolution for raw JSON subscription config")
                ServerAddressResolver.Result(
                    server = server,
                    attempted = false,
                    selectedAddress = null,
                    candidates = emptyList(),
                )
            } else {
                timedStep("Server address resolution") {
                    serverAddressResolver.resolve(server)
                }
            }
            val xrayServer = resolvedServer.server
            if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
                fail("Could not resolve ${server.address} before starting xray")
                return
            }
            if (resolvedServer.selectedAddress != null) {
                log.append(
                    LogSource.APP,
                    "Resolved ${server.address} to ${resolvedServer.selectedAddress} (${resolvedServer.candidates.size} candidates)",
                )
            }

            val appRoutingPlan = appRoutingPlanner.build(
                baseTunName = tunName,
                baseRouteTable = routeTable,
                includeProxyRoutes = true,
                defaultProxyServer = xrayServer,
            )
            if (appRoutingPlan.proxyRoutes.isNotEmpty() || appRoutingPlan.directUids.isNotEmpty()) {
                log.append(
                    LogSource.APP,
                    "App routing: ${appRoutingPlan.proxyRoutes.sumOf { route ->
                        appRoutingPlan.tunRoutes.firstOrNull { it.tunName == route.tunName }?.uids?.size ?: 0
                    }} apps assigned to ${appRoutingPlan.proxyRoutes.size} proxy route(s), ${appRoutingPlan.directUids.size} apps direct",
                )
            }

            val configJson = configGenerator.generate(
                server = xrayServer,
                tunName = tunName,
                fwmark = fwmark,
                dnsServers = runtimeSettings.dnsServers,
                domesticDnsServers = runtimeSettings.domesticDnsServers,
                logLevel = runtimeSettings.logLevel,
                defaultOutbound = runtimeSettings.defaultOutbound,
                bypassLan = runtimeSettings.bypassLan,
                routingRules = runtimeSettings.routingRules,
                appProxyRoutes = appRoutingPlan.proxyRoutes,
                physicalInterface = physicalRoute.dev,
            )
            xrayBinary.writeConfig(configJson)
            log.append(LogSource.APP, "Config written to ${xrayBinary.configPath()}")

            log.append(LogSource.APP, "Starting xray process...")
            val binDir = context.filesDir.resolve("bin").absolutePath
            val pid = timedStep("xray process launch") {
                processSupervisor.start(binDir)
            }

            if (pid <= 0) {
                fail("Could not determine xray process ID after launch")
                return
            }

            log.append(LogSource.APP, "xray running with PID $pid")

            stateFile.write(
                XrayState(
                    xrayPid = pid, tunName = tunName,
                    serverName = server.name,
                    fwmark = fwmark, routeMark = routeMark, routeTable = routeTable, bypassTable = bypassTable,
                    appProxyServerIds = appRoutingPlan.proxyServerIds,
                    physicalInterface = physicalRoute.dev,
                    physicalGateway = physicalRoute.gateway,
                    physicalTable = physicalRoute.table,
                )
            )

            log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
            val tunSetup = timedStep("TUN setup") {
                tunManager.configureTun(tunName) { isProcessAlive(pid) }
            }
            if (!tunSetup.success) {
                val diagnosticsStage = if (tunSetup.processExited) "tun-exit" else "tun-failure"
                diagnostics.logNamespaceDiagnostics(stage = diagnosticsStage, tunName = tunName, xrayPid = pid)
                if (tunSetup.processExited) {
                    fail("xray crashed: ${processSupervisor.readCrashReason()}")
                } else {
                    fail(tunSetup.error ?: "TUN interface $tunName did not come up within timeout")
                }
                return
            }
            log.append(LogSource.APP, "TUN interface $tunName is up")

            appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
                log.append(LogSource.APP, "Waiting for app TUN interface '${route.tunName}'...")
                val appTunSetup = timedStep("App TUN setup ${index + 1}") {
                    tunManager.configureTun(
                        tunName = route.tunName,
                        addressCidr = TunManager.appTunAddressCidr(index + 1),
                    ) { isProcessAlive(pid) }
                }
                if (!appTunSetup.success) {
                    val diagnosticsStage = if (appTunSetup.processExited) "app-tun-exit" else "app-tun-failure"
                    diagnostics.logNamespaceDiagnostics(stage = diagnosticsStage, tunName = route.tunName, xrayPid = pid)
                    if (appTunSetup.processExited) {
                        fail("xray crashed: ${processSupervisor.readCrashReason()}")
                    } else {
                        fail(appTunSetup.error ?: "TUN interface ${route.tunName} did not come up within timeout")
                    }
                    return
                }
                log.append(LogSource.APP, "App TUN interface ${route.tunName} is up")
            }

            val bypassUids = runtimeBypassUids(appRoutingPlan.directUids)
            log.append(
                LogSource.APP,
                "Applying IP routing (tunTable=$routeTable, bypassTable=$bypassTable, fwmark=$fwmark, ${bypassUids.size} apps direct, ${appRoutingPlan.tunRoutes.size} app proxy route(s))...",
            )
            val routingResult = timedStep("IP routing setup") {
                tunManager.applyRouting(
                    tunName = tunName,
                    fwmark = fwmark,
                    routeTable = routeTable,
                    bypassTable = bypassTable,
                    physicalRoute = physicalRoute,
                    bypassUids = bypassUids,
                    appTunRoutes = appRoutingPlan.tunRoutes,
                    routeProfileIds = appRoutingPlan.routeProfileIds,
                )
            }
            if (!routingResult.success) {
                fail("Failed to apply IP routing: ${routingResult.error ?: "unknown error"}")
                return
            }
            log.append(LogSource.APP, "IP routing applied")

            stateFile.write(
                XrayState(
                    xrayPid = pid, tunName = tunName,
                    serverName = server.name,
                    nftTableCreated = false, ipRulesApplied = true,
                    fwmark = fwmark, routeMark = routeMark, routeTable = routeTable, bypassTable = bypassTable,
                    appProxyServerIds = appRoutingPlan.proxyServerIds,
                    physicalInterface = physicalRoute.dev,
                    physicalGateway = physicalRoute.gateway,
                    physicalTable = physicalRoute.table,
                )
            )

            log.append(LogSource.APP, "Connected to ${server.name}")
            log.append(
                LogSource.APP,
                "Connection setup finished in ${SystemClock.elapsedRealtime() - connectStartedAt} ms",
            )
            stateHolder.update(
                ConnectionState.Connected(
                    serverName = server.name,
                    corePid = pid,
                    tunName = tunName,
                    physicalInterface = physicalRoute.dev,
                    physicalGateway = physicalRoute.gateway,
                    physicalTable = physicalRoute.table,
                )
            )
        } catch (e: Exception) {
            fail(e.message ?: "Unknown error")
        }
    }

    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean =
        activeRoutingUpdater.applyAppRoutingChanges(
            connectedState = connectedState,
            tunName = runtimeSettings.tunName,
            fwmark = runtimeSettings.fwmark,
            routeTable = runtimeSettings.routeTable,
        )

    suspend fun reapplyPhysicalRoutingForNetworkChange(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult =
        activeRoutingUpdater.reapplyPhysicalRoutingForNetworkChange(
            connectedState = connectedState,
            tunName = runtimeSettings.tunName,
            fwmark = runtimeSettings.fwmark,
            routeTable = runtimeSettings.routeTable,
        )

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? {
        if (!shell.open()) return null
        return tunManager.detectPhysicalRoute(tunName)
    }

    suspend fun detectPhysicalInterface(tunName: String): String? =
        detectPhysicalRoute(tunName)?.dev

    suspend fun disconnect() {
        disconnect(updateState = true)
    }

    suspend fun disconnect(updateState: Boolean) {
        if (updateState) {
            stateHolder.update(ConnectionState.Disconnecting)
            log.append(LogSource.APP, "Disconnecting...")
        }
        cleanupManager.ensureCleanState()
        if (updateState) {
            log.append(LogSource.APP, "Disconnected")
            stateHolder.update(ConnectionState.Disconnected)
        }
    }

    private suspend fun verifyCaptivePortalAccess(): Boolean {
        log.append(LogSource.APP, "Checking for captive portal...")
        return when (val result = captivePortalDetector.check()) {
            CaptivePortalDetector.Result.Clear -> {
                log.append(LogSource.APP, "Captive portal check passed")
                true
            }
            is CaptivePortalDetector.Result.Captive -> {
                fail(
                    "Captive portal detected. Sign in to the network before starting VPN. ${result.reason}",
                    cleanState = false,
                )
                false
            }
            is CaptivePortalDetector.Result.Unavailable -> {
                log.append(LogSource.APP, "Captive portal check skipped: ${result.reason}")
                true
            }
        }
    }

    private suspend fun fail(message: String, cleanState: Boolean = true) {
        log.append(LogSource.APP, "ERROR: $message")
        if (cleanState) {
            cleanupManager.ensureCleanState()
        }
        stateHolder.update(ConnectionState.Error(message))
    }

    suspend fun isProcessAlive(pid: Int): Boolean =
        processSupervisor.isAlive(pid)

    suspend fun killProcess(pid: Int, signal: Int = 15): Boolean =
        processSupervisor.kill(pid, signal)

    suspend fun readProcessResidentMemoryMb(pid: Int): Long? =
        processSupervisor.readResidentMemoryMb(pid)

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = context.applicationInfo.uid
        return if (appUid > 0) directUids + appUid else directUids
    }

    private suspend fun <T> timedStep(label: String, block: suspend () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            log.append(LogSource.APP, "$label took ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

}
