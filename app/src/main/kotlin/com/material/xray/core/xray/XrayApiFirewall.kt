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
        val chainAActive = hasOutputJump(chainA)
        val chainBActive = hasOutputJump(chainB)
        val activeChain = when {
            chainAActive -> chainA
            chainBActive -> chainB
            else -> null
        }
        val replacementChain = if (activeChain == chainA) chainB else chainA
        val previousChain = if (replacementChain == chainA) chainB else chainA

        // Build a complete replacement before linking it into OUTPUT. A live API
        // therefore remains protected by the previous chain throughout restoration.
        if (!removeChain(replacementChain)) return false
        val configured = execute(
            listOf(
                "$IPTABLES -N $replacementChain",
                "$IPTABLES -A $replacementChain -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port " +
                    "-m owner --uid-owner $appUid -j ACCEPT",
                "$IPTABLES -A $replacementChain -p tcp -d $XRAY_API_LOOPBACK_ADDRESS --dport $port -j REJECT",
                "$IPTABLES -A $replacementChain -j RETURN",
            ).joinToString(" && "),
        )
        if (!configured.isSuccess) {
            removeChain(replacementChain)
            return false
        }

        val activated = execute("$IPTABLES -I OUTPUT 1 -j $replacementChain")
        if (!activated.isSuccess) {
            removeChain(replacementChain)
            return false
        }

        return removeChain(previousChain)
    }

    suspend fun remove(appUid: Int): Boolean {
        if (appUid <= 0) return false
        val removedA = removeChain(chainName(appUid, "a"))
        val removedB = removeChain(chainName(appUid, "b"))
        return removedA && removedB
    }

    private suspend fun hasOutputJump(chainName: String): Boolean = execute(
        "$IPTABLES -C OUTPUT -j $chainName",
    ).isSuccess

    private suspend fun removeChain(chainName: String): Boolean {
        val ruleset = execute("$IPTABLES -S")
        if (!ruleset.isSuccess) return false
        if (ruleset.output.lineSequence().none { it.trim() == "-N $chainName" }) return true
        return execute(
            "while $IPTABLES -C OUTPUT -j $chainName >/dev/null 2>&1; do " +
                "$IPTABLES -D OUTPUT -j $chainName || exit 1; " +
                "done; " +
                "$IPTABLES -F $chainName && $IPTABLES -X $chainName",
        ).isSuccess
    }

    private fun chainName(appUid: Int, slot: String): String = "mxray_api_${appUid}_$slot"

    private companion object {
        const val IPTABLES = "iptables -w"
    }
}
