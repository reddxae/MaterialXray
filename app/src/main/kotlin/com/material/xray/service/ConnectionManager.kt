package com.material.xray.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import com.material.xray.core.app.AppInventory
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.CleanupManager
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XRAY_API_SOCKET_NAME_PREFIX
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XrayStatsClient
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

class ConnectionManager(
    private val context: Context,
    private val shell: RootShell,
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
    private val serverAddressResolver = ServerAddressResolver(context)
    private val tunManager = TunManager(shell)
    private val cleanupManager = CleanupManager(context, shell)
    private val stateFile = StateFile(context)
    private var xrayStatsClient = XrayStatsClient()
    private val processSupervisor = XrayProcessSupervisor(
        environment = AndroidXrayRuntimeEnvironment(context),
        commandRunner = RootShellCommandRunner(shell),
        xrayBinary = XrayBinaryProcessBinary(xrayBinary),
        log = log,
    )
    private val userProcessSupervisor = UserXrayProcessSupervisor(
        environment = AndroidXrayRuntimeEnvironment(context),
        xrayBinary = XrayBinaryProcessBinary(xrayBinary),
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
    private var runningViaVpnService = false

    suspend fun connect(
        server: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        vpnInterface: ParcelFileDescriptor? = null,
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
            xrayStatsClient = XrayStatsClient(xrayApiSocketName)

            writeXrayConfig(xrayServer, runtimeSettings, useRootService, appRoutingPlan, physicalRouteResult.route, xrayApiSocketName)
            val pid = startXrayProcess(useRootService, vpnInterface)

            if (pid <= 0) {
                fail("Could not determine xray process ID after launch")
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
            fail(e.message ?: "Unknown error")
        } catch (e: SecurityException) {
            fail(e.message ?: "Unknown error")
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Unknown error")
        } catch (e: IllegalStateException) {
            fail(e.message ?: "Unknown error")
        } catch (e: SerializationException) {
            fail(e.message ?: "Unknown error")
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
                cleanupManager.ensureCleanState(fallbackTunName = tunName)
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
            shell.open()
        }
        if (!rootGranted) {
            fail("Root access denied")
            return false
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
        return true
    }

    private suspend fun prepareVpnServiceRuntime(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (vpnInterface == null) {
            fail("VPN permission is required")
            return false
        }
        log.append(LogSource.APP, "Using Android VpnService")
        userProcessSupervisor.stop()
        return true
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
                fail("xray binary not found")
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

    private suspend fun detectPhysicalRoute(useRootService: Boolean, tunName: String): PhysicalRouteResult {
        if (!useRootService) return PhysicalRouteResult(success = true, route = null)

        val route = timedStep("Physical route detection") {
            tunManager.detectPhysicalRoute(tunName)
        }
        if (route == null) {
            fail("Could not detect physical network route for Xray bypass")
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
            ServerAddressResolver.Result(
                server = server,
                attempted = false,
                selectedAddress = null,
                candidates = emptyList(),
            )
        } else {
            timedStep("Server address resolution") {
                serverAddressResolver.resolve(server, allowIpv6)
            }
        }
        if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
            fail("Could not resolve ${server.address} before starting xray")
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
            appProxyRoutes = appRoutingPlan.proxyRoutes,
            physicalInterface = physicalRoute?.dev,
            xrayApiSocketName = xrayApiSocketName,
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
        val binDir = context.filesDir.resolve("bin").absolutePath
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
        stateFile.write(
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
                physicalInterface = physicalRoute?.dev ?: "VpnService",
                physicalGateway = physicalRoute?.gateway,
                physicalTable = physicalRoute?.table,
            ),
        )
    }

    private suspend fun waitForRootTun(useRootService: Boolean, tunName: String, pid: Int): Boolean {
        if (!useRootService) return true

        log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
        val tunSetup = timedStep("TUN setup") {
            tunManager.configureTun(tunName) { isProcessAlive(pid) }
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
                tunManager.configureTun(
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
            fail("xray crashed: ${processSupervisor.readCrashReason()}")
        } else {
            fail(tunSetup.error ?: "TUN interface $tunName did not come up within timeout")
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
            tunManager.applyRouting(
                tunName = tunName,
                fwmark = fwmark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                physicalRoute = requireNotNull(physicalRoute),
                bypassUids = bypassUids,
                appTunRoutes = appRoutingPlan.tunRoutes,
                routeProfileIds = appRoutingPlan.routeProfileIds,
            )
        }
        if (!routingResult.success) {
            fail("Failed to apply IP routing: ${routingResult.error ?: "unknown error"}")
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
            "Connection setup finished in ${SystemClock.elapsedRealtime() - connectStartedAt} ms",
        )
        stateHolder.update(
            ConnectionState.Connected(
                serverName = server.name,
                corePid = pid,
                tunName = tunName,
                physicalInterface = physicalRoute?.dev ?: "VpnService",
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
    ): Boolean = activeRoutingUpdater.applyAppRoutingChanges(
        connectedState = connectedState,
        tunName = runtimeSettings.tunName,
        fwmark = runtimeSettings.fwmark,
        routeTable = runtimeSettings.routeTable,
    )

    suspend fun reapplyPhysicalRoutingForNetworkChange(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult = activeRoutingUpdater.reapplyPhysicalRoutingForNetworkChange(
        connectedState = connectedState,
        tunName = runtimeSettings.tunName,
        fwmark = runtimeSettings.fwmark,
        routeTable = runtimeSettings.routeTable,
    )

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? {
        if (!shell.open()) return null
        return tunManager.detectPhysicalRoute(tunName)
    }

    suspend fun detectPhysicalInterface(tunName: String): String? = detectPhysicalRoute(tunName)?.dev

    suspend fun disconnect() {
        disconnect(updateState = true, fastRootCleanup = true)
    }

    suspend fun disconnect(updateState: Boolean, fastRootCleanup: Boolean = false) {
        if (updateState) {
            stateHolder.update(ConnectionState.Disconnecting)
            log.append(LogSource.APP, "Disconnecting...")
        }
        val wasRunningViaVpnService = runningViaVpnService
        userProcessSupervisor.stop()
        if (wasRunningViaVpnService) {
            stateFile.delete()
        } else {
            val hasRootState = stateFile.read() != null
            val knownStateStopped = if (fastRootCleanup) {
                cleanupManager.ensureKnownStateStopped()
            } else {
                false
            }
            if (!knownStateStopped && (hasRootState || updateState)) {
                cleanupManager.ensureCleanState()
            }
        }
        runningViaVpnService = false
        if (updateState) {
            log.append(LogSource.APP, "Disconnected")
            stateHolder.update(ConnectionState.Disconnected)
        }
    }

    private suspend fun fail(message: String, cleanState: Boolean = true) {
        log.append(LogSource.APP, "ERROR: $message")
        if (cleanState) {
            userProcessSupervisor.stop()
            if (!runningViaVpnService) {
                cleanupManager.ensureCleanState()
            } else {
                stateFile.delete()
            }
            runningViaVpnService = false
        }
        stateHolder.update(ConnectionState.Error(message))
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
        withContext(Dispatchers.IO) { readUserProcessSocketCount(pid) }
    } else {
        val result = shell.execute("ls -l /proc/$pid/fd 2>/dev/null | grep -c 'socket:'")
        result.output.trim().toIntOrNull()
    }

    suspend fun readOutboundTrafficStatsBytes(): Map<String, Long> = xrayStatsClient.queryOutboundTrafficStatsBytes()

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = context.applicationInfo.uid
        return if (appUid > 0) directUids + appUid else directUids
    }

    private fun readUserProcessSocketCount(pid: Int): Int? = File("/proc/$pid/fd")
        .takeIf { it.isDirectory }
        ?.listFiles()
        ?.count { fd -> runCatching { Os.readlink(fd.absolutePath).startsWith("socket:") }.getOrDefault(false) }

    private fun nextXrayApiSocketName(): String = "$XRAY_API_SOCKET_NAME_PREFIX-${android.os.Process.myPid()}-${SystemClock.elapsedRealtime()}"

    private suspend fun <T> timedStep(label: String, block: suspend () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            log.append(LogSource.APP, "$label took ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }
}
