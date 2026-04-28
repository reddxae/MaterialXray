package com.material.xray.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.material.xray.core.root.RootShell
import com.material.xray.core.root.RootShell.NetworkNamespace
import com.material.xray.core.xray.*
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.RoutingRule
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import java.io.FileOutputStream

class ConnectionManager(
    private val context: Context,
    private val shell: RootShell,
    private val configGenerator: ConfigGenerator,
    private val geoDataManager: GeoDataManager,
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val stateHolder: ConnectionStateHolder,
    private val log: LogBuffer,
    private val onXrayLogReady: () -> Unit = {},
) {
    private val xrayBinary = XrayBinary(context)
    private val serverAddressResolver = ServerAddressResolver()
    private val tunManager = TunManager(shell)
    private val cleanupManager = CleanupManager(context, shell)
    private val stateFile = StateFile(context)
    private val logFile get() = "${context.filesDir.absolutePath}/xray.log"

    private data class AppRoutingPlan(
        val directUids: Set<Int>,
        val proxyRoutes: List<ConfigGenerator.AppProxyRoute>,
        val tunRoutes: List<TunManager.AppTunRoute>,
        val proxyServerIds: List<Long>,
    )

    suspend fun connect(
        server: ServerConfig,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        dnsServers: String,
        logLevel: XrayLogLevel,
        defaultOutbound: XrayOutbound,
        bypassLan: Boolean,
        routingRules: List<RoutingRule>,
        transitionState: ConnectionState = ConnectionState.Connecting,
        cleanStateFirst: Boolean = true,
    ) {
        stateHolder.update(transitionState)
        val connectStartedAt = SystemClock.elapsedRealtime()
        val routeMark = routeTable
        val bypassTable = routeTable + 1
        log.clear()
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")

        try {
            if (cleanStateFirst) {
                log.append(LogSource.APP, "Cleaning up previous state...")
                timedStep("Cleanup") {
                    cleanupManager.ensureCleanState()
                }
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
            ensureNativeRuntimeExemptions()
            logNamespaceDiagnostics(stage = "shell")

            prepareLogFile()
            onXrayLogReady()

            log.append(LogSource.APP, "Extracting xray binary...")
            val xrayReady = timedStep("xray binary extraction") {
                xrayBinary.ensureExtracted()
            }
            if (!xrayReady) {
                fail("xray binary not found — check assets")
                return
            }
            log.append(LogSource.APP, "xray binary ready at ${xrayBinary.binaryPath}")

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

            val appRoutingPlan = buildAppRoutingPlan(tunName, routeTable, includeProxyRoutes = true)
            if (appRoutingPlan.proxyRoutes.isNotEmpty() || appRoutingPlan.directUids.isNotEmpty()) {
                log.append(
                    LogSource.APP,
                    "App routing: ${appRoutingPlan.proxyRoutes.sumOf { route ->
                        appRoutingPlan.tunRoutes.firstOrNull { it.tunName == route.tunName }?.uids?.size ?: 0
                    }} apps assigned to ${appRoutingPlan.proxyRoutes.size} proxy route(s), ${appRoutingPlan.directUids.size} apps direct",
                )
            }

            val configJson = configGenerator.generate(
                xrayServer,
                tunName,
                fwmark,
                dnsServers,
                logLevel,
                defaultOutbound,
                bypassLan,
                routingRules,
                appProxyRoutes = appRoutingPlan.proxyRoutes,
                physicalInterface = physicalRoute.dev,
            )
            xrayBinary.writeConfig(configJson)
            log.append(LogSource.APP, "Config written to ${xrayBinary.configPath()}")

            log.append(LogSource.APP, "Starting xray process...")
            val binDir = context.filesDir.resolve("bin").absolutePath
            val pid = timedStep("xray process launch") {
                startXrayProcess(binDir)
            }

            if (pid <= 0) {
                fail("Could not determine xray process ID after launch")
                return
            }

            log.append(LogSource.APP, "xray running with PID $pid")
            logNamespaceDiagnostics(stage = "xray-start", tunName = tunName, xrayPid = pid)

            stateFile.write(
                XrayState(
                    xrayPid = pid, tunName = tunName,
                    fwmark = fwmark, routeMark = routeMark, routeTable = routeTable, bypassTable = bypassTable,
                    appProxyServerIds = appRoutingPlan.proxyServerIds,
                )
            )

            log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
            val tunSetup = timedStep("TUN setup") {
                tunManager.configureTun(tunName) { isProcessAlive(pid) }
            }
            if (!tunSetup.success) {
                val diagnosticsStage = if (tunSetup.processExited) "tun-exit" else "tun-failure"
                logNamespaceDiagnostics(stage = diagnosticsStage, tunName = tunName, xrayPid = pid)
                if (tunSetup.processExited) {
                    fail("xray crashed: ${readCrashReason()}")
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
                    logNamespaceDiagnostics(stage = diagnosticsStage, tunName = route.tunName, xrayPid = pid)
                    if (appTunSetup.processExited) {
                        fail("xray crashed: ${readCrashReason()}")
                    } else {
                        fail(appTunSetup.error ?: "TUN interface ${route.tunName} did not come up within timeout")
                    }
                    return
                }
                log.append(LogSource.APP, "App TUN interface ${route.tunName} is up")
            }

            val bypassUids = appRoutingPlan.directUids
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
                    nftTableCreated = false, ipRulesApplied = true,
                    fwmark = fwmark, routeMark = routeMark, routeTable = routeTable, bypassTable = bypassTable,
                    appProxyServerIds = appRoutingPlan.proxyServerIds,
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
                )
            )
        } catch (e: Exception) {
            fail(e.message ?: "Unknown error")
        }
    }

    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
    ): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val persistedState = stateFile.read()
        if (persistedState == null) {
            log.append(LogSource.APP, "Fast app routing update skipped: active state file is missing")
            return false
        }
        if (persistedState.tunName != tunName || persistedState.routeTable != routeTable || persistedState.fwmark != fwmark) {
            log.append(LogSource.APP, "Fast app routing update skipped: active routing settings changed")
            return false
        }
        if (!isProcessAlive(connectedState.corePid)) {
            log.append(LogSource.APP, "Fast app routing update skipped: xray process is not running")
            return false
        }

        val appRoutingPlan = try {
            buildAppRoutingPlan(tunName, routeTable, includeProxyRoutes = false)
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
            tunManager.detectPhysicalRoute(tunName)
        }
        if (physicalRoute == null) {
            log.append(LogSource.APP, "Fast app routing update skipped: could not detect physical network route")
            return false
        }

        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            val appTunSetup = timedStep("App TUN check ${index + 1}") {
                tunManager.configureTun(
                    tunName = route.tunName,
                    addressCidr = TunManager.appTunAddressCidr(index + 1),
                ) { isProcessAlive(connectedState.corePid) }
            }
            if (!appTunSetup.success) {
                log.append(LogSource.APP, "Fast app routing update skipped: ${appTunSetup.error ?: "app TUN ${route.tunName} is unavailable"}")
                return false
            }
        }

        val bypassTable = routeTable + 1
        val routingResult = timedStep("IP routing update") {
            tunManager.applyRouting(
                tunName = tunName,
                fwmark = fwmark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                physicalRoute = physicalRoute,
                bypassUids = appRoutingPlan.directUids,
                appTunRoutes = appRoutingPlan.tunRoutes,
                managedAppRouteCount = persistedState.appProxyServerIds.size,
            )
        }
        if (!routingResult.success) {
            log.append(LogSource.APP, "Fast app routing update skipped: ${routingResult.error ?: "unknown routing error"}")
            return false
        }

        stateFile.write(
            persistedState.copy(
                ipRulesApplied = true,
                appProxyServerIds = appRoutingPlan.proxyServerIds,
            )
        )
        log.append(LogSource.APP, "App routing changes applied in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return true
    }

    suspend fun detectPhysicalInterface(tunName: String): String? {
        if (!shell.open()) return null
        return tunManager.detectPhysicalRoute(tunName)?.dev
    }

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

    private suspend fun fail(message: String) {
        log.append(LogSource.APP, "ERROR: $message")
        cleanupManager.ensureCleanState()
        stateHolder.update(ConnectionState.Error(message))
    }

    private suspend fun prepareLogFile() {
        shell.execute("rm -f $logFile")
        FileOutputStream(context.filesDir.resolve("xray.log"), false).use { }
    }

    private suspend fun startXrayProcess(binDir: String): Int {
        val command = buildString {
            append("cd ${shellQuote(binDir)} && ")
            append("${shellQuote(xrayBinary.binaryPath)} run -c ${shellQuote(xrayBinary.configPath())}")
            append(" > ${shellQuote(logFile)} 2>&1 & printf '%s' \$!")
        }
        val result = shell.execute(command)
        return result.output.trim().toIntOrNull() ?: -1
    }

    suspend fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return shell.execute("kill -0 $pid 2>/dev/null").isSuccess
    }

    suspend fun killProcess(pid: Int, signal: Int = 15): Boolean {
        if (pid <= 0) return false
        return shell.execute("kill -$signal $pid 2>/dev/null").isSuccess
    }

    private suspend fun buildAppRoutingPlan(
        baseTunName: String,
        baseRouteTable: Int,
        includeProxyRoutes: Boolean,
    ): AppRoutingPlan {
        val directUids = appBypassDao.getExcluded()
            .map { it.uid }
            .filter { it > 0 }
            .toSet()

        val proxyAssignments = appBypassDao.getProxyAssignments()
            .filter { it.uid > 0 && it.serverId != null }
            .groupBy { requireNotNull(it.serverId) }
            .toSortedMap()

        if (proxyAssignments.isEmpty()) {
            return AppRoutingPlan(
                directUids = directUids,
                proxyRoutes = emptyList(),
                tunRoutes = emptyList(),
                proxyServerIds = emptyList(),
            )
        }

        if (proxyAssignments.size > MAX_APP_PROXY_ROUTES) {
            log.append(
                LogSource.APP,
                "Only the first $MAX_APP_PROXY_ROUTES app proxy server groups can be active at once; extra groups are ignored",
            )
        }

        val proxyRoutes = mutableListOf<ConfigGenerator.AppProxyRoute>()
        val tunRoutes = mutableListOf<TunManager.AppTunRoute>()
        val proxyServerIds = mutableListOf<Long>()

        proxyAssignments.entries.take(MAX_APP_PROXY_ROUTES).forEachIndexed { index, (serverId, assignments) ->
            val routeIndex = index + 1
            val routeTunName = TunManager.appTunName(baseTunName, routeIndex)
            val uids = assignments.map { it.uid }.filter { it > 0 }.toSet()
            if (uids.isEmpty()) return@forEachIndexed

            proxyServerIds += serverId
            tunRoutes += TunManager.AppTunRoute(
                tunName = routeTunName,
                routeTable = TunManager.appRouteTable(baseRouteTable, routeIndex),
                uids = uids,
            )

            if (!includeProxyRoutes) {
                return@forEachIndexed
            }

            val serverEntity = serverRepository.getById(serverId)
            if (serverEntity == null) {
                log.append(LogSource.APP, "Skipping app route for missing server id=$serverId")
                proxyServerIds.removeLast()
                tunRoutes.removeLast()
                return@forEachIndexed
            }

            val parsedServerResult = runCatching { serverRepository.parseConfig(serverEntity) }
            if (parsedServerResult.isFailure) {
                log.append(
                    LogSource.APP,
                    "Skipping app route for ${serverEntity.name}: ${parsedServerResult.exceptionOrNull()?.message}",
                )
                proxyServerIds.removeLast()
                tunRoutes.removeLast()
                return@forEachIndexed
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
            proxyRoutes += ConfigGenerator.AppProxyRoute(
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
        )
    }

    suspend fun readProcessResidentMemoryMb(pid: Int): Long? {
        val rssKb = readProcessResidentMemoryKb(pid) ?: return null
        return (rssKb + KILOBYTES_PER_MEGABYTE - 1) / KILOBYTES_PER_MEGABYTE
    }

    private suspend fun readCrashReason(lines: Int = 80): String {
        val crashLog = shell.execute("tail -n $lines ${shellQuote(logFile)} 2>/dev/null").output.trim()
        return crashLog.lines().lastOrNull { it.isNotBlank() } ?: "xray process exited"
    }

    private suspend fun ensureNativeRuntimeExemptions() {
        val packageName = context.packageName
        val packageUid = context.applicationInfo.uid
        val powerManager = context.getSystemService(PowerManager::class.java)

        val wasIgnoringBatteryOptimizations =
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        if (wasIgnoringBatteryOptimizations) {
            log.append(LogSource.APP, "Battery optimizations already disabled for $packageName")
        } else {
            val result = shell.execute("cmd deviceidle whitelist +${shellQuote(packageName)}")
            if (result.isSuccess) {
                val nowIgnoringBatteryOptimizations =
                    powerManager?.isIgnoringBatteryOptimizations(packageName) == true
                log.append(
                    LogSource.APP,
                    if (nowIgnoringBatteryOptimizations) {
                        "Added $packageName to the device idle whitelist"
                    } else {
                        "Requested device idle whitelist for $packageName"
                    },
                )
            } else {
                log.append(
                    LogSource.APP,
                    "Could not update device idle whitelist for $packageName: ${
                        result.error.ifBlank { result.output }.ifBlank { "unknown error" }
                    }",
                )
            }
        }

        if (packageUid > 0) {
            val netPolicyResult = shell.execute("cmd netpolicy add restrict-background-whitelist $packageUid")
            if (netPolicyResult.isSuccess) {
                log.append(LogSource.APP, "Added uid=$packageUid to the background-data allowlist")
            } else if (netPolicyResult.exitCode != 0) {
                val details = netPolicyResult.error.ifBlank { netPolicyResult.output }.trim()
                log.append(
                    LogSource.APP,
                    "Background-data allowlist update skipped for uid=$packageUid${
                        details.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""
                    }",
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val lowPowerStandbyExempt = powerManager?.isExemptFromLowPowerStandby() == true
            log.append(
                LogSource.APP,
                if (lowPowerStandbyExempt) {
                    "Low Power Standby exemption is active for $packageName"
                } else {
                    "Low Power Standby exemption is not active for $packageName"
                },
            )
        }
    }

    private suspend fun readProcessResidentMemoryKb(pid: Int): Long? {
        if (pid <= 0) return null

        val statusResult = shell.execute("awk '/^VmRSS:/ { print \$2 }' /proc/$pid/status 2>/dev/null")
        statusResult.output.trim().toLongOrNull()?.let { return it }

        val statmResult = shell.execute("awk '{ print \$2 }' /proc/$pid/statm 2>/dev/null")
        val rssPages = statmResult.output.trim().toLongOrNull() ?: return null
        return rssPages * DEFAULT_MEMORY_PAGE_KB
    }

    private suspend fun <T> timedStep(label: String, block: suspend () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            log.append(LogSource.APP, "$label took ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private suspend fun logNamespaceDiagnostics(
        stage: String,
        tunName: String? = null,
        xrayPid: Int? = null,
    ) {
        logCommand(
            "$stage/current-netns",
            "printf 'pid=%s self=%s pid1=%s\\n' \"$$\" \"$(readlink /proc/$$/ns/net 2>/dev/null)\" \"$(readlink /proc/1/ns/net 2>/dev/null)\"",
            NetworkNamespace.CURRENT,
        )

        if (shell.defaultNetworkNamespace() == NetworkNamespace.INIT) {
            logCommand(
                "$stage/init-netns",
                "printf 'pid=%s self=%s pid1=%s\\n' \"$$\" \"$(readlink /proc/$$/ns/net 2>/dev/null)\" \"$(readlink /proc/1/ns/net 2>/dev/null)\"",
                NetworkNamespace.INIT,
            )
        }

        if (tunName != null) {
            logCommand(
                "$stage/current-link",
                "ip link show $tunName 2>/dev/null | head -n 1",
                NetworkNamespace.CURRENT,
            )
            if (shell.defaultNetworkNamespace() == NetworkNamespace.INIT) {
                logCommand(
                    "$stage/init-link",
                    "ip link show $tunName 2>/dev/null | head -n 1",
                    NetworkNamespace.INIT,
                )
            }
        }

        if (xrayPid != null && xrayPid > 0) {
            logCommand(
                "$stage/xray-proc",
                "if [ -d /proc/$xrayPid ]; then printf 'pid=$xrayPid net=%s\\n' \"$(readlink /proc/$xrayPid/ns/net 2>/dev/null)\"; tr '\\0' ' ' </proc/$xrayPid/cmdline 2>/dev/null; else echo 'pid=$xrayPid missing'; fi",
                NetworkNamespace.CURRENT,
            )
            if (tunName != null) {
                logCommand(
                    "$stage/xray-link",
                    "if [ -d /proc/$xrayPid ]; then nsenter -t $xrayPid -n -- ip link show $tunName 2>/dev/null | head -n 1; fi",
                    NetworkNamespace.CURRENT,
                )
            }
        }
    }

    private suspend fun logCommand(
        label: String,
        command: String,
        namespace: NetworkNamespace,
    ) {
        val result = shell.execute(command, namespace)
        val outputLines = result.output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val errorLines = result.error.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

        if (outputLines.isEmpty() && errorLines.isEmpty()) {
            log.append(LogSource.APP, "$label: exit=${result.exitCode}")
            return
        }

        outputLines.forEach { log.append(LogSource.APP, "$label: $it") }
        errorLines.forEach { log.append(LogSource.APP, "$label stderr: $it") }
        if (!result.isSuccess) {
            log.append(LogSource.APP, "$label: exit=${result.exitCode}")
        }
    }

    companion object {
        private const val DEFAULT_MEMORY_PAGE_KB = 4L
        private const val KILOBYTES_PER_MEGABYTE = 1024L
        private const val MAX_APP_PROXY_ROUTES = 64
    }
}
