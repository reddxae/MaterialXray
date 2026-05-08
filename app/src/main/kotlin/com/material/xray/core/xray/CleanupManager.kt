package com.material.xray.core.xray

import android.content.Context
import com.material.xray.core.nftables.NftablesManager
import com.material.xray.core.root.RootShell

class CleanupManager(
    context: Context,
    private val shell: RootShell,
) {
    private val stateFile = StateFile(context)
    private val nftables = NftablesManager(shell)
    private val tunManager = TunManager(shell)
    private val configPath = context.filesDir.resolve("config.json").absolutePath

    suspend fun ensureCleanState(fallbackTunName: String = "xray0") {
        val state = stateFile.read()

        // 1. Kill orphaned xray process
        if (state != null && state.xrayPid > 0) {
            stopProcess(state.xrayPid)
        }
        killProcessesUsingConfig()

        removeRuntimeState(state, fallbackTunName)
    }

    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0"): Boolean {
        val state = stateFile.read() ?: return false
        stopProcess(state.xrayPid)
        killProcessesUsingConfig()
        removeRuntimeState(state, fallbackTunName)
        return true
    }

    private suspend fun removeRuntimeState(state: XrayState?, fallbackTunName: String) {
        nftables.remove()

        val tunName = state?.tunName ?: fallbackTunName
        val fwmark = state?.fwmark ?: 255
        val routeMark = state?.routeMark ?: 100
        val routeTable = state?.routeTable ?: 100
        val appRouteCount = state?.appProxyServerIds?.size?.takeIf { it > 0 } ?: 0
        tunManager.removeRouting(fwmark, routeMark, routeTable, tunName, appRouteCount)

        stateFile.delete()
    }

    private suspend fun killProcessesUsingConfig() {
        val command = buildString {
            append("pids=\"\"; ")
            append("config=${shellQuote(configPath)}; ")
            append("for pid in \$(pidof xray 2>/dev/null); do ")
            append("cmdline=\$(tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null) || continue; ")
            append("case \"\$cmdline\" in *\"\$config\"*) pids=\"\$pids \$pid\";; esac; ")
            append("done; ")
            append("if [ -n \"\$pids\" ]; then ")
            append("kill \$pids 2>/dev/null; ")
            append("sleep 0.05; ")
            append("for pid in \$pids; do kill -0 \"\$pid\" 2>/dev/null && kill -9 \"\$pid\" 2>/dev/null; done; ")
            append("fi")
        }
        shell.execute(command)
    }

    private suspend fun stopProcess(pid: Int) {
        if (pid <= 0) return
        val command = buildString {
            append("if kill -0 $pid 2>/dev/null; then ")
            append("kill $pid 2>/dev/null; ")
            append("sleep 0.05; ")
            append("kill -0 $pid 2>/dev/null && kill -9 $pid 2>/dev/null; ")
            append("fi")
        }
        shell.execute(command)
    }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
