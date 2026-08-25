package com.material.xray.core.xray

import com.material.xray.core.root.RootShell

internal class XrayApiFirewall(
    private val execute: suspend (String) -> RootShell.Result,
) {
    constructor(shell: RootShell) : this(execute = { command -> shell.execute(command) })

    suspend fun apply(port: Int, appUid: Int): Boolean {
        if (port !in 1..65_535 || appUid <= 0) return false
        val chainA = chainName(appUid, "a")
        val chainB = chainName(appUid, "b")
        return execute(buildApplyCommand(chainA, chainB, port, appUid)).isSuccess
    }

    suspend fun remove(appUid: Int): Boolean {
        if (appUid <= 0) return false
        return execute(
            buildString {
                append(shellHelpers())
                append("; refresh_ruleset || exit 1; status=0")
                append("; remove_chain ${chainName(appUid, "a")} || status=1")
                append("; remove_chain ${chainName(appUid, "b")} || status=1")
                append("; exit \$status")
            },
        ).isSuccess
    }

    internal fun buildApplyCommand(chainA: String, chainB: String, port: Int, appUid: Int): String = buildString {
        append(shellHelpers())
        append("; refresh_ruleset || exit 1")
        append("; if has_jump $chainA; then active=$chainA; replacement=$chainB")
        append("; elif has_jump $chainB; then active=$chainB; replacement=$chainA")
        append("; else active=''; replacement=$chainA; fi")
        append("; remove_chain \"\$replacement\" || exit 1")
        append("; bulk_setup() { printf '*filter\\n:%s - [0:0]\\n")
        append("-A %s -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port -m owner --uid-owner $appUid -j ACCEPT\\n")
        append("-A %s -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port -j REJECT\\n")
        append("-A %s -j RETURN\\n-I OUTPUT 1 -j %s\\nCOMMIT\\n' ")
        append("\"\$replacement\" \"\$replacement\" \"\$replacement\" \"\$replacement\" \"\$replacement\" | ")
        append("iptables-restore --noflush; }")
        append("; fallback_setup() { if ! $IPTABLES -N \"\$replacement\"")
        append(" || ! $IPTABLES -A \"\$replacement\" -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port")
        append(" -m owner --uid-owner $appUid -j ACCEPT")
        append(" || ! $IPTABLES -A \"\$replacement\" -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port -j REJECT")
        append(" || ! $IPTABLES -A \"\$replacement\" -j RETURN; then")
        append(" return 1; fi; $IPTABLES -I OUTPUT 1 -j \"\$replacement\"; }")
        append("; if ! bulk_setup && ! fallback_setup; then remove_chain \"\$replacement\"; exit 1; fi")
        append("; [ -z \"\$active\" ] || remove_chain \"\$active\"")
    }

    private fun shellHelpers(): String = "refresh_ruleset() { ruleset=\$($IPTABLES -S) || return 1; }" +
        "; has_jump() { printf '%s\\n' \"\$ruleset\" | grep -Fqx -- \"-A OUTPUT -j \$1\"; }" +
        "; chain_exists() { printf '%s\\n' \"\$ruleset\" | grep -Fqx -- \"-N \$1\"; }" +
        "; remove_chain() { " +
        "refresh_ruleset || return 1" +
        "; while has_jump \"\$1\"; do $IPTABLES -D OUTPUT -j \"\$1\" || return 1" +
        "; refresh_ruleset || return 1; done" +
        "; if chain_exists \"\$1\"; then $IPTABLES -F \"\$1\" && $IPTABLES -X \"\$1\" || return 1" +
        "; refresh_ruleset || return 1; fi; }"

    private fun chainName(appUid: Int, slot: String): String = "mxray_api_${appUid}_$slot"

    private companion object {
        const val IPTABLES = "iptables -w"
    }
}
