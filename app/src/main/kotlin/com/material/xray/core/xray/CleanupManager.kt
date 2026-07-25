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
    private val apiFirewall = XrayApiFirewall(shell)
    private val appUid = context.applicationInfo.uid
    private val configPath = context.filesDir.resolve("config.json").absolutePath

    suspend fun ensureCleanState(fallbackTunName: String = "xray0"): Boolean {
        val state = stateFile.read()
        val processesStopped = stopOwnedProcesses(state?.xrayPid)
        val runtimeRemoved = removeRuntimeState(state, fallbackTunName)
        if (processesStopped && runtimeRemoved) stateFile.delete()
        return processesStopped && runtimeRemoved
    }

    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0"): Boolean {
        val state = stateFile.read() ?: return false
        val processesStopped = stopOwnedProcesses(state.xrayPid)
        val runtimeRemoved = removeRuntimeState(state, fallbackTunName)
        if (processesStopped && runtimeRemoved) stateFile.delete()
        return processesStopped && runtimeRemoved
    }

    private suspend fun removeRuntimeState(state: XrayState?, fallbackTunName: String): Boolean {
        val firewallRemoved = apiFirewall.remove(appUid)
        val nftablesRemoved = nftables.remove(required = state?.nftTableCreated == true)

        val tunName = state?.tunName ?: fallbackTunName
        val fwmark = state?.fwmark ?: 255
        val routeMark = state?.routeMark ?: 100
        val routeTable = state?.routeTable ?: 100
        val appRouteCount = state?.appProxyServerIds?.size?.takeIf { it > 0 } ?: 0
        val routingRemoved = tunManager.removeRouting(fwmark, routeMark, routeTable, tunName, appRouteCount)
        return firewallRemoved && nftablesRemoved && routingRemoved
    }

    private suspend fun stopOwnedProcesses(persistedPid: Int?): Boolean = shell.execute(
        ownedProcessStopCommand(configPath, persistedPid),
    ).isSuccess
}

internal fun ownedProcessStopCommand(configPath: String, persistedPid: Int?): String = buildString {
    append("config=${shellQuote(configPath)}; candidates=${shellQuote(persistedPid?.takeIf { it > 0 }?.toString().orEmpty())}; ")
    append("is_owned() { [ -e \"/proc/\$1\" ] || return 1; [ -r \"/proc/\$1/cmdline\" ] || return 1; ")
    append("cmdline=\$(tr '\\0' ' ' < \"/proc/\$1/cmdline\" 2>/dev/null) || return 1; ")
    append("case \"\$cmdline\" in *\"\$config\"*) return 0;; *) return 1;; esac; }; ")
    append("for pid in \$(pidof xray 2>/dev/null); do case \" \$candidates \" in *\" \$pid \"*) ;; ")
    append("*) candidates=\"\$candidates \$pid\";; esac; done; owned=''; ")
    append("for pid in \$candidates; do case \"\$pid\" in ''|*[!0-9]*) continue;; esac; ")
    append("if is_owned \"\$pid\"; then owned=\"\$owned \$pid\"; fi; done; ")
    append("for pid in \$owned; do if is_owned \"\$pid\"; then kill \"\$pid\" 2>/dev/null || ")
    append("{ if is_owned \"\$pid\"; then exit 1; fi; }; fi; done; ")
    append("attempt=0; while [ \$attempt -lt 20 ]; do alive=''; for pid in \$owned; do ")
    append("if is_owned \"\$pid\"; then alive=\"\$alive \$pid\"; fi; ")
    append("done; [ -z \"\$alive\" ] && break; ")
    append("sleep 0.05; attempt=\$((attempt + 1)); done; ")
    append("for pid in \$alive; do if is_owned \"\$pid\"; then kill -9 \"\$pid\" 2>/dev/null || ")
    append("{ if is_owned \"\$pid\"; then exit 1; fi; }; fi; done; ")
    append("[ -z \"\$alive\" ] || sleep 0.05; for pid in \$alive; do if is_owned \"\$pid\"; then exit 1; ")
    append("fi; done; true")
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
