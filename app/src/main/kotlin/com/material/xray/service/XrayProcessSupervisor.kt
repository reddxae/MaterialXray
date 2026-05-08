package com.material.xray.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.XrayBinary
import kotlinx.coroutines.delay
import java.io.FileOutputStream
import java.io.File

internal interface XrayProcessProbe {
    suspend fun isAlive(pid: Int): Boolean
}

internal interface RootCommandRunner {
    suspend fun execute(command: String): RootShell.Result
}

internal class RootShellCommandRunner(
    private val shell: RootShell,
) : RootCommandRunner {
    override suspend fun execute(command: String): RootShell.Result = shell.execute(command)
}

internal interface XrayProcessBinary {
    val rootBinaryPath: String
    val androidBinaryPath: String?
    fun configPath(): String
}

internal class XrayBinaryProcessBinary(
    private val xrayBinary: XrayBinary,
) : XrayProcessBinary {
    override val rootBinaryPath: String
        get() = xrayBinary.rootBinaryPath

    override val androidBinaryPath: String?
        get() = xrayBinary.androidBinaryPath

    override fun configPath(): String = xrayBinary.configPath()
}

internal interface XrayRuntimeEnvironment {
    val filesDir: File
    val packageName: String
    val packageUid: Int

    fun isIgnoringBatteryOptimizations(): Boolean?
    fun isExemptFromLowPowerStandby(): Boolean?
}

internal class AndroidXrayRuntimeEnvironment(
    private val context: Context,
) : XrayRuntimeEnvironment {
    override val filesDir: File
        get() = context.filesDir

    override val packageName: String
        get() = context.packageName

    override val packageUid: Int
        get() = context.applicationInfo.uid

    override fun isIgnoringBatteryOptimizations(): Boolean? =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)

    override fun isExemptFromLowPowerStandby(): Boolean? =
        context.getSystemService(PowerManager::class.java)?.isExemptFromLowPowerStandby()
}

internal class XrayProcessSupervisor(
    private val environment: XrayRuntimeEnvironment,
    private val commandRunner: RootCommandRunner,
    private val xrayBinary: XrayProcessBinary,
    private val log: LogBuffer,
) : XrayProcessProbe {
    val logFile: String
        get() = "${environment.filesDir.absolutePath}/xray.log"

    suspend fun prepareLogFile() {
        commandRunner.execute("rm -f $logFile")
        FileOutputStream(environment.filesDir.resolve("xray.log"), false).use { }
    }

    suspend fun start(binDir: String): Int {
        val command = buildString {
            append("config=${shellQuote(xrayBinary.configPath())}; ")
            append("cd ${shellQuote(binDir)} && ")
            append("env ")
            xrayAssetEnvironment(binDir).forEach { (key, value) ->
                append("${shellQuote("$key=$value")} ")
            }
            append("sh -c 'exec \"\$@\"' xray ")
            append("${shellQuote(xrayBinary.rootBinaryPath)} run -c \"\$config\"")
            append(" > ${shellQuote(logFile)} 2>&1 & ")
            append("launcher=\$!; ")
            append("found=\"\"; ")
            append("i=0; ")
            append("while [ \$i -lt 20 ]; do ")
            append("for pid in \$(pidof xray 2>/dev/null); do ")
            append("cmdline=\$(tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null) || continue; ")
            append("case \"\$cmdline\" in *\"\$config\"*) found=\"\$pid\"; break;; esac; ")
            append("done; ")
            append("[ -n \"\$found\" ] && break; ")
            append("sleep 0.05; ")
            append("i=\$((i + 1)); ")
            append("done; ")
            append("printf '%s' \"\${found:-\$launcher}\"")
        }
        val result = commandRunner.execute(command)
        return result.output.trim().toIntOrNull() ?: -1
    }

    override suspend fun isAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return commandRunner.execute("kill -0 $pid 2>/dev/null").isSuccess
    }

    suspend fun kill(pid: Int, signal: Int = 15): Boolean {
        if (pid <= 0) return false
        return commandRunner.execute("kill -$signal $pid 2>/dev/null").isSuccess
    }

    suspend fun readResidentMemoryMb(pid: Int): Long? {
        val rssKb = readResidentMemoryKb(pid) ?: return null
        return (rssKb + KILOBYTES_PER_MEGABYTE - 1) / KILOBYTES_PER_MEGABYTE
    }

    suspend fun readCrashReason(lines: Int = 80): String {
        val crashLog = commandRunner.execute("tail -n $lines ${shellQuote(logFile)} 2>/dev/null").output.trim()
        return crashLog.lines().lastOrNull { it.isNotBlank() } ?: "xray process exited"
    }

    suspend fun ensureNativeRuntimeExemptions() {
        val packageName = environment.packageName
        val packageUid = environment.packageUid

        val wasIgnoringBatteryOptimizations = environment.isIgnoringBatteryOptimizations() == true
        if (wasIgnoringBatteryOptimizations) {
            log.append(LogSource.APP, "Battery optimizations already disabled for $packageName")
        } else {
            val result = commandRunner.execute("cmd deviceidle whitelist +${shellQuote(packageName)}")
            if (result.isSuccess) {
                val nowIgnoringBatteryOptimizations = environment.isIgnoringBatteryOptimizations() == true
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
            val netPolicyResult = commandRunner.execute("cmd netpolicy add restrict-background-whitelist $packageUid")
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
            val lowPowerStandbyExempt = environment.isExemptFromLowPowerStandby() == true
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

    private suspend fun readResidentMemoryKb(pid: Int): Long? {
        if (pid <= 0) return null

        val statusResult = commandRunner.execute("awk '/^VmRSS:/ { print \$2 }' /proc/$pid/status 2>/dev/null")
        statusResult.output.trim().toLongOrNull()?.let { return it }

        val statmResult = commandRunner.execute("awk '{ print \$2 }' /proc/$pid/statm 2>/dev/null")
        val rssPages = statmResult.output.trim().toLongOrNull() ?: return null
        return rssPages * DEFAULT_MEMORY_PAGE_KB
    }

    private companion object {
        private const val DEFAULT_MEMORY_PAGE_KB = 4L
        private const val KILOBYTES_PER_MEGABYTE = 1024L
    }
}

internal class UserXrayProcessSupervisor(
    private val environment: XrayRuntimeEnvironment,
    private val xrayBinary: XrayProcessBinary,
    private val processLauncher: UserXrayProcessLauncher = AndroidUserXrayProcessLauncher(),
) : XrayProcessProbe {
    private var pid: Int = -1
    private val logFile: File
        get() = environment.filesDir.resolve("xray.log")

    fun prepareLogFile() {
        FileOutputStream(logFile, false).use { }
    }

    fun start(binDir: String, tunFd: Int): Int {
        val binaryPath = requireNotNull(xrayBinary.androidBinaryPath) { "Android xray binary is unavailable" }
        pid = processLauncher.start(
            binaryPath = binaryPath,
            configPath = xrayBinary.configPath(),
            workingDir = binDir,
            logPath = logFile.absolutePath,
            tunFd = tunFd,
            environment = xrayAssetEnvironment(binDir),
        )
        return pid
    }

    override suspend fun isAlive(pid: Int): Boolean {
        if (pid <= 0 || this.pid != pid) return false
        return processLauncher.isAlive(pid)
    }

    suspend fun kill(pid: Int, signal: Int = 15): Boolean {
        if (pid <= 0 || this.pid != pid) return false
        return processLauncher.kill(pid, signal)
    }

    suspend fun stop() {
        val stoppedPid = pid.takeIf { it > 0 } ?: return
        processLauncher.kill(stoppedPid, signal = 15)
        if (!waitUntilStopped(stoppedPid, STOP_GRACE_TIMEOUT_MS)) {
            processLauncher.kill(stoppedPid, signal = 9)
            waitUntilStopped(stoppedPid, KILL_GRACE_TIMEOUT_MS)
        }
        pid = -1
    }

    suspend fun readResidentMemoryMb(pid: Int): Long? {
        val rssKb = File("/proc/$pid/status")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return null
        return (rssKb + KILOBYTES_PER_MEGABYTE - 1) / KILOBYTES_PER_MEGABYTE
    }

    suspend fun readCrashReason(lines: Int = 80): String =
        logFile.takeIf { it.isFile }
            ?.readLines()
            ?.takeLast(lines)
            ?.lastOrNull { it.isNotBlank() }
            ?: "xray process exited"

    private companion object {
        private const val KILOBYTES_PER_MEGABYTE = 1024L
        private const val STOP_GRACE_TIMEOUT_MS = 1_000L
        private const val KILL_GRACE_TIMEOUT_MS = 500L
        private const val STOP_POLL_INTERVAL_MS = 50L
    }

    private suspend fun waitUntilStopped(pid: Int, timeoutMs: Long): Boolean {
        var elapsedMs = 0L
        while (elapsedMs <= timeoutMs) {
            if (!processLauncher.isAlive(pid)) return true
            delay(STOP_POLL_INTERVAL_MS)
            elapsedMs += STOP_POLL_INTERVAL_MS
        }
        return false
    }
}

internal interface UserXrayProcessLauncher {
    fun start(
        binaryPath: String,
        configPath: String,
        workingDir: String,
        logPath: String,
        tunFd: Int,
        environment: Map<String, String>,
    ): Int

    fun isAlive(pid: Int): Boolean

    fun kill(pid: Int, signal: Int): Boolean
}

class AndroidUserXrayProcessLauncher : UserXrayProcessLauncher {
    override fun start(
        binaryPath: String,
        configPath: String,
        workingDir: String,
        logPath: String,
        tunFd: Int,
        environment: Map<String, String>,
    ): Int {
        val env = (System.getenv() + environment)
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()
        return nativeStart(binaryPath, configPath, workingDir, logPath, tunFd, env)
    }

    override fun isAlive(pid: Int): Boolean = nativeIsAlive(pid)

    override fun kill(pid: Int, signal: Int): Boolean = nativeKill(pid, signal)

    private companion object {
        init {
            System.loadLibrary("xray_launcher")
        }

        @JvmStatic
        external fun nativeStart(
            binaryPath: String,
            configPath: String,
            workingDir: String,
            logPath: String,
            tunFd: Int,
            environment: Array<String>,
        ): Int

        @JvmStatic
        external fun nativeIsAlive(pid: Int): Boolean

        @JvmStatic
        external fun nativeKill(pid: Int, signal: Int): Boolean
    }
}

private fun xrayAssetEnvironment(assetDir: String): Map<String, String> =
    mapOf(
        "xray.location.asset" to assetDir,
        "XRAY_LOCATION_ASSET" to assetDir,
    )

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
