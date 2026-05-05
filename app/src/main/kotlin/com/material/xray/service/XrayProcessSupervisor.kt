package com.material.xray.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.XrayBinary
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
    val binaryPath: String
    fun configPath(): String
}

internal class XrayBinaryProcessBinary(
    private val xrayBinary: XrayBinary,
) : XrayProcessBinary {
    override val binaryPath: String
        get() = xrayBinary.binaryPath

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
            append("cd ${shellQuote(binDir)} && ")
            append("${shellQuote(xrayBinary.binaryPath)} run -c ${shellQuote(xrayBinary.configPath())}")
            append(" > ${shellQuote(logFile)} 2>&1 & printf '%s' \$!")
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

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
