package com.material.xray.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import com.material.xray.core.root.RootShell
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal interface XrayProcessProbe {
    suspend fun isAlive(pid: Int): Boolean
}

internal interface RootXrayProcessController : XrayProcessProbe {
    suspend fun prepareLogFile()
    suspend fun start(binDir: String): Int
    suspend fun kill(pid: Int, signal: Int = 15): Boolean
    suspend fun readResidentMemoryMb(pid: Int): Long?
    suspend fun readCrashReason(lines: Int = 80): String
    suspend fun ensureNativeRuntimeExemptions()
}

internal interface UserXrayProcessController : XrayProcessProbe {
    suspend fun prepareLogFile()

    /**
     * Starts the core against [tunFd], which the caller still owns. Implementations must not
     * suspend before the descriptor has been duplicated into the child.
     */
    fun start(binDir: String, tunFd: Int): Int
    suspend fun kill(pid: Int, signal: Int = 15): Boolean
    suspend fun stop()
    suspend fun stopOrphan(pid: Int)
    fun requestStop()
    suspend fun readResidentMemoryMb(pid: Int): Long?
    suspend fun readCrashReason(lines: Int = 80): String
    fun readActiveConnectionCount(pid: Int): Int?
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

internal interface XrayRuntimeEnvironment {
    val filesDir: File
    val packageName: String
    val packageUid: Int
    val memoryPageSizeKb: Long?

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

    override val memoryPageSizeKb: Long? by lazy {
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }
            .getOrNull()
            ?.takeIf { it > 0L && it % BYTES_PER_KILOBYTE == 0L }
            ?.div(BYTES_PER_KILOBYTE)
    }

    override fun isIgnoringBatteryOptimizations(): Boolean? = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)

    override fun isExemptFromLowPowerStandby(): Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(PowerManager::class.java)?.isExemptFromLowPowerStandby()
    } else {
        null
    }
}

internal class XrayProcessSupervisor(
    private val environment: XrayRuntimeEnvironment,
    private val commandRunner: RootCommandRunner,
    private val xrayBinary: XrayProcessBinary,
    private val certificateBundle: RootCertificateBundle,
    private val log: LogBuffer,
) : RootXrayProcessController {
    val logFile: String
        get() = environment.filesDir.resolve(XRAY_LOG_FILE_NAME).absolutePath

    override suspend fun prepareLogFile() {
        commandRunner.execute("rm -f $logFile")
        withContext(Dispatchers.IO) {
            FileOutputStream(environment.filesDir.resolve(XRAY_LOG_FILE_NAME), false).use { }
        }
    }

    override suspend fun start(binDir: String): Int {
        val certificateBundleFile = environment.filesDir.resolve(ROOT_CERTIFICATE_BUNDLE_FILE)
        certificateBundle.update(certificateBundleFile)
        val command = buildString {
            append("config=${shellQuote(xrayBinary.configPath())}; ")
            append("cd ${shellQuote(binDir)} && ")
            append("env ")
            rootXrayEnvironment(binDir, certificateBundleFile.absolutePath).forEach { (key, value) ->
                append("${shellQuote("$key=$value")} ")
            }
            append("sh -c 'exec \"\$@\"' xray ")
            append("${shellQuote(xrayBinary.rootBinaryPath)} run -c \"\$config\"")
            append(" > ${shellQuote(logFile)} 2>&1 & ")
            append("launcher=\$!; ")
            append("found=\"\"; ")
            append("is_owned() { [ -r \"/proc/\$1/cmdline\" ] || return 1; ")
            append("cmdline=\$(cat -v \"/proc/\$1/cmdline\" 2>/dev/null) || return 1; ")
            append("case \"\$cmdline\" in *\"\$config\"*) return 0;; *) return 1;; esac; }; ")
            append("i=0; ")
            append("while [ \$i -lt 5 ]; do ")
            append("if is_owned \"\$launcher\"; then found=\"\$launcher\"; break; fi; ")
            append("sleep 0.01; i=\$((i + 1)); ")
            append("done; ")
            append("i=0; ")
            append("while [ -z \"\$found\" ] && [ \$i -lt 20 ]; do ")
            append("for pid in \$(pidof xray 2>/dev/null); do ")
            append("if is_owned \"\$pid\"; then found=\"\$pid\"; break; fi; ")
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
        val configPath = shellQuote(xrayBinary.configPath())
        val command = "config=$configPath; " +
            "kill -0 $pid 2>/dev/null && " +
            "[ \"\$(awk '/^State:/ { print \$2 }' /proc/$pid/status 2>/dev/null)\" != Z ] && " +
            "cmdline=\$(cat -v /proc/$pid/cmdline 2>/dev/null) && " +
            "case \"\$cmdline\" in *\"\$config\"*) true;; *) false;; esac"
        return commandRunner.execute(command).isSuccess
    }

    override suspend fun kill(pid: Int, signal: Int): Boolean {
        if (pid <= 0) return false
        return commandRunner.execute("kill -$signal $pid 2>/dev/null").isSuccess
    }

    override suspend fun readResidentMemoryMb(pid: Int): Long? {
        val rssKb = readResidentMemoryKb(pid) ?: return null
        return (rssKb + KILOBYTES_PER_MEGABYTE - 1) / KILOBYTES_PER_MEGABYTE
    }

    override suspend fun readCrashReason(lines: Int): String {
        val crashLog = commandRunner.execute("tail -n $lines ${shellQuote(logFile)} 2>/dev/null").output.trim()
        return crashLog.lines().lastOrNull { it.isNotBlank() } ?: "xray process exited"
    }

    override suspend fun ensureNativeRuntimeExemptions() {
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

        val statmResult = commandRunner.execute(
            "awk '{ print \$2; exit }' /proc/$pid/statm 2>/dev/null",
        )
        val rssPages = statmResult.output.trim().toLongOrNull() ?: return null
        val pageSizeKb = environment.memoryPageSizeKb ?: return null
        return rssPages * pageSizeKb
    }

    private companion object {
        private const val KILOBYTES_PER_MEGABYTE = 1024L
        private const val ROOT_CERTIFICATE_BUNDLE_FILE = "xray-ca-certificates.pem"
    }
}

internal class UserXrayProcessSupervisor(
    private val environment: XrayRuntimeEnvironment,
    private val xrayBinary: XrayProcessBinary,
    private val processLauncher: UserXrayProcessLauncher = AndroidUserXrayProcessLauncher(),
) : UserXrayProcessController {
    // Probes and lifecycle commands arrive from different dispatchers, so the tracked PID needs
    // cross-thread visibility.
    @Volatile
    private var pid: Int = -1
    private val logFile: File
        get() = environment.filesDir.resolve(XRAY_LOG_FILE_NAME)

    override suspend fun prepareLogFile() {
        withContext(Dispatchers.IO) { FileOutputStream(logFile, false).use { } }
    }

    // Deliberately not dispatched elsewhere. The caller owns the tunnel ParcelFileDescriptor and
    // only lends the raw descriptor number, so duplicating it into the child has to happen before
    // the caller can reach a suspension point and let a teardown close it underneath us. fork and
    // execve do not wait on IO, so there is nothing to move off the caller's thread anyway.
    override fun start(binDir: String, tunFd: Int): Int {
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
        val alive = processLauncher.isAlive(pid)
        if (!alive && this.pid == pid) {
            // The liveness probe reaps the child, so from here the kernel is free to recycle the
            // PID. Dropping it now keeps later stop/kill calls from signalling whatever process
            // inherits the number.
            this.pid = -1
        }
        return alive
    }

    override suspend fun kill(pid: Int, signal: Int): Boolean {
        if (pid <= 0 || this.pid != pid) return false
        return processLauncher.kill(pid, signal)
    }

    override suspend fun stop() {
        val stoppedPid = pid.takeIf { it > 0 } ?: return
        stopProcess(stoppedPid)
        pid = -1
    }

    override suspend fun stopOrphan(pid: Int) {
        if (pid > 0 && this.pid != pid) stopProcess(pid)
    }

    override fun requestStop() {
        val stoppedPid = pid.takeIf { it > 0 } ?: return
        processLauncher.kill(stoppedPid, signal = 15)
        pid = -1
    }

    override suspend fun readResidentMemoryMb(pid: Int): Long? {
        if (pid <= 0) return null
        val rssPages = runCatching {
            readStatmResidentPages(File("/proc/$pid/statm"))
        }.getOrNull() ?: return null
        val pageSizeKb = environment.memoryPageSizeKb ?: return null
        val rssKb = rssPages * pageSizeKb
        return (rssKb + KILOBYTES_PER_MEGABYTE - 1) / KILOBYTES_PER_MEGABYTE
    }

    override suspend fun readCrashReason(lines: Int): String = runCatching {
        logFile.takeIf { it.isFile }
            ?.readLines()
            ?.takeLast(lines)
            ?.lastOrNull { it.isNotBlank() }
    }.getOrNull() ?: "xray process exited"

    override fun readActiveConnectionCount(pid: Int): Int? = File("/proc/$pid/fd")
        .takeIf { it.isDirectory }
        ?.listFiles()
        ?.count { fd -> runCatching { Os.readlink(fd.absolutePath).startsWith("socket:") }.getOrDefault(false) }

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

    private suspend fun stopProcess(pid: Int) {
        processLauncher.kill(pid, signal = 15)
        if (!waitUntilStopped(pid, STOP_GRACE_TIMEOUT_MS)) {
            processLauncher.kill(pid, signal = 9)
            waitUntilStopped(pid, KILL_GRACE_TIMEOUT_MS)
        }
    }
}

internal fun parseStatmResidentPages(line: String?): Long? {
    if (line == null) return null
    return parseStatmResidentPages(line.toByteArray(Charsets.US_ASCII), line.length)
}

private fun readStatmResidentPages(file: File): Long? = FileInputStream(file).use { input ->
    val buffer = ByteArray(STATM_BUFFER_SIZE)
    val length = input.read(buffer)
    parseStatmResidentPages(buffer, length)
}

internal fun parseStatmResidentPages(buffer: ByteArray, length: Int): Long? {
    if (length <= 0 || length > buffer.size) return null
    var index = 0
    while (index < length && !buffer[index].isAsciiWhitespace()) index++
    if (index == length) return null
    while (index < length && buffer[index].isAsciiWhitespace()) index++
    val start = index
    var value = 0L
    while (index < length && buffer[index] in ASCII_ZERO..ASCII_NINE) {
        value = value * 10L + (buffer[index] - ASCII_ZERO)
        index++
    }
    if (start == index) return null
    return value
}

private fun Byte.isAsciiWhitespace(): Boolean = this == ASCII_SPACE || this == ASCII_TAB || this == ASCII_NEWLINE

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

private fun xrayAssetEnvironment(assetDir: String): Map<String, String> = mapOf(
    "xray.location.asset" to assetDir,
    "XRAY_LOCATION_ASSET" to assetDir,
)

private fun rootXrayEnvironment(assetDir: String, certificateBundlePath: String): Map<String, String> = xrayAssetEnvironment(assetDir) + mapOf(
    // Root mode uses a Linux build, so Go does not discover Android's CA store itself.
    "SSL_CERT_FILE" to certificateBundlePath,
)

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

internal const val XRAY_LOG_FILE_NAME = "xray.log"

private const val BYTES_PER_KILOBYTE = 1024L
private const val STATM_BUFFER_SIZE = 128
private const val ASCII_ZERO: Byte = 48
private const val ASCII_NINE: Byte = 57
private const val ASCII_SPACE: Byte = 32
private const val ASCII_TAB: Byte = 9
private const val ASCII_NEWLINE: Byte = 10
