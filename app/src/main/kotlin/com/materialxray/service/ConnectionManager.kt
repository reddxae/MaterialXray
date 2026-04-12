package com.materialxray.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.materialxray.core.root.RootShell
import com.materialxray.core.root.RootShell.NetworkNamespace
import com.materialxray.core.xray.*
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.model.ConnectionState
import com.materialxray.model.RoutingRule
import com.materialxray.model.ServerConfig
import java.io.FileOutputStream

class ConnectionManager(
    private val context: Context,
    private val shell: RootShell,
    private val configGenerator: ConfigGenerator,
    private val geoDataManager: GeoDataManager,
    private val appBypassDao: AppBypassDao,
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

    suspend fun connect(
        server: ServerConfig,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        dnsServers: String,
        routingRules: List<RoutingRule>,
        transitionState: ConnectionState = ConnectionState.Connecting,
    ) {
        stateHolder.update(transitionState)
        val connectStartedAt = SystemClock.elapsedRealtime()
        val routeMark = routeTable
        val bypassTable = routeTable + 1
        log.clear()
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")

        try {
            log.append(LogSource.APP, "Cleaning up previous state...")
            timedStep("Cleanup") {
                cleanupManager.ensureCleanState()
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

            val resolvedServer = timedStep("Server address resolution") {
                serverAddressResolver.resolve(server)
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

            val configJson = configGenerator.generate(
                xrayServer,
                tunName,
                fwmark,
                dnsServers,
                routingRules,
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

            stateFile.write(XrayState(
                xrayPid = pid, tunName = tunName,
                fwmark = fwmark, routeMark = routeMark, routeTable = routeTable, bypassTable = bypassTable,
            ))

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

            val bypassUids = appBypassDao.getExcluded().map { it.uid }.toSet()
            log.append(
                LogSource.APP,
                "Applying IP routing (tunTable=$routeTable, bypassTable=$bypassTable, fwmark=$fwmark, ${bypassUids.size} apps bypassed)...",
            )
            val routingResult = timedStep("IP routing setup") {
                tunManager.applyRouting(tunName, fwmark, routeTable, bypassTable, physicalRoute, bypassUids)
            }
            if (!routingResult.success) {
                fail("Failed to apply IP routing: ${routingResult.error ?: "unknown error"}")
                return
            }
            log.append(LogSource.APP, "IP routing applied")

            stateFile.write(XrayState(
                xrayPid = pid, tunName = tunName,
                nftTableCreated = false, ipRulesApplied = true,
                fwmark = fwmark, routeMark = routeMark, routeTable = routeTable, bypassTable = bypassTable,
            ))

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
                    "Could not update device idle whitelist for $packageName: ${result.error.ifBlank { result.output }.ifBlank { "unknown error" }}",
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
                    "Background-data allowlist update skipped for uid=$packageUid${details.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}",
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
    }
}
