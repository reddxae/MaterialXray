package com.material.xray.core.xray

import android.content.Context
import com.material.xray.core.root.RootShell
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TproxyCompatibility {
    data object Unknown : TproxyCompatibility
    data object Checking : TproxyCompatibility

    data class Supported(
        val ipv6: Boolean,
    ) : TproxyCompatibility

    data class Unsupported(
        val reason: Reason,
        val details: String? = null,
    ) : TproxyCompatibility

    enum class Reason {
        RootUnavailable,
        InitNetworkNamespaceUnavailable,
        IptablesMangleUnavailable,
        OwnerMatchUnavailable,
        MarkTargetUnavailable,
        TproxyIpv4Unavailable,
        Ipv6BlockingUnavailable,
        ListenerInspectionUnavailable,
        PolicyRoutingUnavailable,
        RouteTableConflict,
        TproxyIpv6Unavailable,
        MarkNamespaceConflict,
        ProbeCleanupFailed,
        CommandTimedOut,
    }
}

/**
 * Whether a verdict is a real statement about the kernel. A denied root shell, a timeout or a foreign
 * rule conflict says nothing about TPROXY support, so it must not be used to demote the user's backend.
 */
internal fun TproxyCompatibility.isConclusive(): Boolean = when (this) {
    is TproxyCompatibility.Supported -> true
    is TproxyCompatibility.Unsupported -> when (reason) {
        TproxyCompatibility.Reason.IptablesMangleUnavailable,
        TproxyCompatibility.Reason.OwnerMatchUnavailable,
        TproxyCompatibility.Reason.MarkTargetUnavailable,
        TproxyCompatibility.Reason.TproxyIpv4Unavailable,
        TproxyCompatibility.Reason.Ipv6BlockingUnavailable,
        TproxyCompatibility.Reason.ListenerInspectionUnavailable,
        TproxyCompatibility.Reason.TproxyIpv6Unavailable,
        TproxyCompatibility.Reason.PolicyRoutingUnavailable,
        -> true
        TproxyCompatibility.Reason.RootUnavailable,
        TproxyCompatibility.Reason.InitNetworkNamespaceUnavailable,
        TproxyCompatibility.Reason.RouteTableConflict,
        TproxyCompatibility.Reason.MarkNamespaceConflict,
        TproxyCompatibility.Reason.ProbeCleanupFailed,
        TproxyCompatibility.Reason.CommandTimedOut,
        -> false
    }
    TproxyCompatibility.Checking,
    TproxyCompatibility.Unknown,
    -> false
}

@Singleton
class TproxyCompatibilityDetector @Inject constructor(
    private val shell: RootShell,
    @ApplicationContext context: Context,
) {
    private val appUid = context.applicationInfo.uid
    private val mutex = Mutex()
    private var probed = false
    private val _state = MutableStateFlow<TproxyCompatibility>(TproxyCompatibility.Unknown)

    /**
     * The verdict for this process. Doubles as the cache, so this flow is the single source of truth and
     * nothing else ever needs to start a probe.
     */
    val state: StateFlow<TproxyCompatibility> = _state.asStateFlow()

    /**
     * TPROXY support is a property of the kernel and ROM. It cannot change while the process lives, so the
     * probe runs exactly once per application lifecycle and every later caller reads the cached verdict.
     * Only an explicit user-initiated [refresh] re-runs it.
     */
    suspend fun detect(): TproxyCompatibility = detect(forceRefresh = false)

    suspend fun refresh(): TproxyCompatibility = detect(forceRefresh = true)

    private suspend fun detect(forceRefresh: Boolean): TproxyCompatibility = mutex.withLock {
        if (probed && !forceRefresh) return@withLock _state.value
        probed = true
        _state.value = TproxyCompatibility.Checking
        runDetection().also { _state.value = it }
    }

    private suspend fun runDetection(): TproxyCompatibility {
        if (!shell.open()) {
            return TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.RootUnavailable)
        }
        if (!shell.open(RootShell.NetworkNamespace.INIT)) {
            return TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.InitNetworkNamespaceUnavailable)
        }

        val collision = shell.execute(markCollisionCommand(appUid))
        if (collision.exitCode == MARK_CONFLICT_EXIT_CODE) {
            return TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.MarkNamespaceConflict)
        }
        if (!collision.isSuccess) {
            return TproxyCompatibility.Unsupported(
                TproxyCompatibility.Reason.PolicyRoutingUnavailable,
                collision.error.takeIf { it.isNotBlank() },
            )
        }
        val rules = shell.execute("ip rule show; ip -6 rule show")
        if (!rules.isSuccess) {
            return TproxyCompatibility.Unsupported(
                TproxyCompatibility.Reason.PolicyRoutingUnavailable,
                rules.error.takeIf { it.isNotBlank() },
            )
        }
        val unownedOverlap = overlappingFwmarkRules(rules.output, MARK_PREFIX, MARK_MASK).any { rule ->
            rule.priority <= TproxyManager.RULE_PRIORITY &&
                (
                    rule.priority != TproxyManager.RULE_PRIORITY ||
                        rule.value != MARK_PREFIX.toUInt() ||
                        rule.mask != MARK_MASK.toUInt()
                    )
        }
        if (unownedOverlap) {
            return TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.MarkNamespaceConflict)
        }

        val ipv4 = runProbe(allowIpv6 = false)
        if (ipv4 !is TproxyCompatibility.Supported) return ipv4

        return resolveDualStackCompatibility(ipv4, runProbe(allowIpv6 = true))
    }

    private suspend fun runProbe(allowIpv6: Boolean): TproxyCompatibility {
        val suffix = (System.nanoTime() and 0xffffff).toString(16)
        val result = shell.execute(probeCommand(suffix, allowIpv6), timeoutMs = PROBE_TIMEOUT_MS)
        if (result.isSuccess) {
            return TproxyCompatibility.Supported(ipv6 = allowIpv6)
        }

        val stage = result.output.lineSequence()
            .lastOrNull { it.startsWith("stage=") }
            ?.substringAfter('=')
        val reason = when {
            result.exitCode == -1 && "timed out" in result.error -> TproxyCompatibility.Reason.CommandTimedOut
            stage == "conflict" -> TproxyCompatibility.Reason.RouteTableConflict
            stage == "cleanup" -> TproxyCompatibility.Reason.ProbeCleanupFailed
            allowIpv6 -> TproxyCompatibility.Reason.TproxyIpv6Unavailable
            stage == "iptables" -> TproxyCompatibility.Reason.IptablesMangleUnavailable
            stage == "owner" -> TproxyCompatibility.Reason.OwnerMatchUnavailable
            stage == "mark" -> TproxyCompatibility.Reason.MarkTargetUnavailable
            stage == "tproxy4" -> TproxyCompatibility.Reason.TproxyIpv4Unavailable
            stage == "ipv6block" -> TproxyCompatibility.Reason.Ipv6BlockingUnavailable
            stage == "tools" -> TproxyCompatibility.Reason.ListenerInspectionUnavailable
            stage == "route4" -> TproxyCompatibility.Reason.PolicyRoutingUnavailable
            else -> TproxyCompatibility.Reason.TproxyIpv4Unavailable
        }
        val details = listOf(result.output, result.error)
            .joinToString(" | ")
            .trim(' ', '|')
            .takeIf { it.isNotBlank() }
        return TproxyCompatibility.Unsupported(reason, details)
    }

    internal companion object {
        const val MARK_PREFIX = 0x0a000000
        const val MARK_MASK = 0x0f000000

        /**
         * The probe must measure kernel capability, never our own live policy routing. It therefore uses a
         * mark outside [MARK_MASK] so the production rule cannot capture it, evaluated at a priority ahead of
         * Android's own `fwmark 0x8000000/0xce00000` rules which would otherwise divert the probe.
         */
        private const val PROBE_MARK = 0x0b000000
        private const val PROBE_MASK = 0x0f000000
        private const val PROBE_PRIORITY_BASE = 11_991
        private const val PROBE_PRIORITY_SLOTS = 8
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val MARK_CONFLICT_EXIT_CODE = 42

        fun markCollisionCommand(appUid: Int): String {
            require(appUid > 0)
            val outputChain = "MXO${appUid.toString(16)}"
            val prefix = "0x${MARK_PREFIX.toString(16)}"
            val mask = "0x${MARK_MASK.toString(16)}"
            return "rules=\$(ip rule show 2>/dev/null) || exit 1; " +
                "case \"\$rules\" in *'fwmark $prefix/$mask'*) " +
                "iptables -t mangle -C OUTPUT -j $outputChain 2>/dev/null || exit $MARK_CONFLICT_EXIT_CODE;; esac; true"
        }

        fun probeCommand(suffix: String, allowIpv6: Boolean): String {
            require(suffix.matches(Regex("[a-f0-9]+")))
            val chains = probeChains(suffix)
            val table = 19_000 + suffix.takeLast(3).toInt(16) % 500
            val priority = PROBE_PRIORITY_BASE + suffix.takeLast(3).toInt(16) % PROBE_PRIORITY_SLOTS
            val prefixHex = "0x${PROBE_MARK.toString(16)}"
            val groupHex = "0x${(PROBE_MARK or 1).toString(16)}"
            val maskHex = "0x${PROBE_MASK.toString(16)}"
            val resources = probeFirewallResources(chains, allowIpv6)
            val cleanup = probeCleanupCommands(resources, table, priority, prefixHex, maskHex, allowIpv6)
            val commands = buildList {
                add("cleanup() { ${cleanup.joinToString("; ")}; }")
                add("fail() { printf 'stage=%s\\n' \"\$1\"; cleanup; exit 1; }")
                add(probeConflictCommand(resources, table, priority))
                addAll(ipv4ProbeCommands(chains, table, priority, prefixHex, groupHex, maskHex, allowIpv6))
                if (allowIpv6) {
                    addAll(ipv6ProbeCommands(chains, table, priority, prefixHex, groupHex, maskHex))
                } else {
                    addAll(ipv6BlockingProbeCommands(chains))
                }
                add("cleanup")
                resources.forEach { resource ->
                    add("${resource.tool} -S ${resource.chain} >/dev/null 2>&1 && fail cleanup")
                }
                add("ip rule show | grep -q 'pref $priority.*lookup $table' && fail cleanup")
                if (allowIpv6) {
                    add("ip -6 rule show | grep -q 'pref $priority.*lookup $table' && fail cleanup")
                }
                add("true")
            }
            return commands.joinToString("; ")
        }

        private fun probeChains(suffix: String): ProbeChains {
            val prefix = "MXP$suffix".take(24)
            return ProbeChains(
                ipv4Prerouting = "${prefix}4P",
                ipv4Output = "${prefix}4O",
                ipv6Prerouting = "${prefix}6P",
                ipv6Output = "${prefix}6O",
                ipv6Filter = "${prefix}6F",
            )
        }

        private fun probeFirewallResources(chains: ProbeChains, allowIpv6: Boolean): List<ProbeFirewallResource> = buildList {
            add(ProbeFirewallResource("iptables -t mangle", "PREROUTING", chains.ipv4Prerouting))
            add(ProbeFirewallResource("iptables -t mangle", "OUTPUT", chains.ipv4Output))
            if (allowIpv6) {
                add(ProbeFirewallResource("ip6tables -t mangle", "PREROUTING", chains.ipv6Prerouting))
            }
            add(ProbeFirewallResource("ip6tables -t mangle", "OUTPUT", chains.ipv6Output))
            if (!allowIpv6) {
                add(ProbeFirewallResource("ip6tables -t filter", "OUTPUT", chains.ipv6Filter))
            }
        }

        private fun probeCleanupCommands(
            resources: List<ProbeFirewallResource>,
            table: Int,
            priority: Int,
            prefixHex: String,
            maskHex: String,
            allowIpv6: Boolean,
        ): List<String> = buildList {
            resources.forEach { resource ->
                add("${resource.tool} -D ${resource.hook} -j ${resource.chain} 2>/dev/null || true")
            }
            resources.forEach { resource ->
                add("${resource.tool} -F ${resource.chain} 2>/dev/null || true")
                add("${resource.tool} -X ${resource.chain} 2>/dev/null || true")
            }
            add("ip rule del fwmark $prefixHex/$maskHex table $table pref $priority 2>/dev/null || true")
            add("ip route del local 0.0.0.0/0 dev lo table $table 2>/dev/null || true")
            if (allowIpv6) {
                add("ip -6 rule del fwmark $prefixHex/$maskHex table $table pref $priority 2>/dev/null || true")
                add("ip -6 route del local ::/0 dev lo table $table 2>/dev/null || true")
            }
        }

        private fun probeConflictCommand(
            resources: List<ProbeFirewallResource>,
            table: Int,
            priority: Int,
        ): String {
            val conflicts = buildList {
                resources.forEach { resource -> add("${resource.tool} -S ${resource.chain} >/dev/null 2>&1") }
                add("[ -n \"\$(ip rule show pref $priority 2>/dev/null)\" ]")
                add("[ -n \"\$(ip -6 rule show pref $priority 2>/dev/null)\" ]")
                add("[ -n \"\$(ip route show table $table 2>/dev/null)\" ]")
                add("[ -n \"\$(ip -6 route show table $table 2>/dev/null)\" ]")
            }
            return "if ${conflicts.joinToString(" || ")}; then printf 'stage=conflict\\n'; exit 43; fi"
        }

        private fun ipv4ProbeCommands(
            chains: ProbeChains,
            table: Int,
            priority: Int,
            prefixHex: String,
            groupHex: String,
            maskHex: String,
            allowIpv6: Boolean,
        ): List<String> {
            val localMatch = if (allowIpv6) "-m addrtype --dst-type LOCAL" else "-d 127.0.0.0/8"
            val onIp = if (allowIpv6) "0.0.0.0" else "127.0.0.1"
            return listOf(
                "iptables -t mangle -N ${chains.ipv4Prerouting} || fail iptables",
                "iptables -t mangle -A ${chains.ipv4Prerouting} -j RETURN || fail iptables",
                "iptables -t mangle -A ${chains.ipv4Prerouting} -p tcp -m mark --mark $groupHex/0xffffffff " +
                    "-j TPROXY --on-ip $onIp --on-port 9 --tproxy-mark $groupHex/0xffffffff || fail tproxy4",
                "iptables -t mangle -A ${chains.ipv4Prerouting} -p udp -m mark --mark $groupHex/0xffffffff " +
                    "-j TPROXY --on-ip $onIp --on-port 9 --tproxy-mark $groupHex/0xffffffff || fail tproxy4",
                "iptables -t mangle -N ${chains.ipv4Output} || fail iptables",
                "iptables -t mangle -A ${chains.ipv4Output} -j RETURN || fail iptables",
                "iptables -t mangle -A ${chains.ipv4Output} -m mark --mark 255/0xffffffff -j RETURN || fail mark",
                "iptables -t mangle -A ${chains.ipv4Output} -m owner --uid-owner 0-1 -j RETURN || fail owner",
                "iptables -t mangle -A ${chains.ipv4Output} $localMatch -p tcp --dport 9 -j DROP || fail iptables",
                "iptables -t mangle -A ${chains.ipv4Output} $localMatch -p udp --dport 9 -j DROP || fail iptables",
                "iptables -t mangle -A ${chains.ipv4Output} -m owner --uid-owner 0-1 -p tcp " +
                    "-j MARK --set-xmark $groupHex/0xffffffff || fail mark",
                "iptables -t mangle -A ${chains.ipv4Output} -m owner --uid-owner 0-1 -p udp " +
                    "-j MARK --set-xmark $groupHex/0xffffffff || fail mark",
                "iptables -t mangle -A ${chains.ipv4Output} -m mark --mark $prefixHex/$maskHex -j RETURN || fail mark",
                "iptables -t mangle -A ${chains.ipv4Output} -m owner --uid-owner 0-1 -j DROP || fail owner",
                "ip route replace local 0.0.0.0/0 dev lo table $table || fail route4",
                "ip rule add fwmark $prefixHex/$maskHex table $table pref $priority || fail route4",
                "iptables -t mangle -I PREROUTING 1 -j ${chains.ipv4Prerouting} || fail iptables",
                "iptables -t mangle -I OUTPUT 1 -j ${chains.ipv4Output} || fail iptables",
                "iptables -t mangle -C PREROUTING -j ${chains.ipv4Prerouting} || fail iptables",
                "iptables -t mangle -C OUTPUT -j ${chains.ipv4Output} || fail iptables",
                "iptables -t mangle -R ${chains.ipv4Output} 1 -j RETURN || fail iptables",
                "ip route get 192.0.2.1 mark $groupHex | grep -q 'dev lo' || fail route4",
                "ss -lnt >/dev/null && ss -lnu >/dev/null || fail tools",
            )
        }

        private fun ipv6ProbeCommands(
            chains: ProbeChains,
            table: Int,
            priority: Int,
            prefixHex: String,
            groupHex: String,
            maskHex: String,
        ): List<String> = listOf(
            "ip6tables -t mangle -N ${chains.ipv6Prerouting} || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Prerouting} -j RETURN || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Prerouting} -p tcp -m mark --mark $groupHex/0xffffffff " +
                "-j TPROXY --on-ip :: --on-port 9 --tproxy-mark $groupHex/0xffffffff || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Prerouting} -p udp -m mark --mark $groupHex/0xffffffff " +
                "-j TPROXY --on-ip :: --on-port 9 --tproxy-mark $groupHex/0xffffffff || fail tproxy6",
            "ip6tables -t mangle -N ${chains.ipv6Output} || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -j RETURN || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m owner --uid-owner 0-1 -j RETURN || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m addrtype --dst-type LOCAL " +
                "-p tcp --dport 9 -j DROP || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m addrtype --dst-type LOCAL " +
                "-p udp --dport 9 -j DROP || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m owner --uid-owner 0-1 -p tcp " +
                "-j MARK --set-xmark $groupHex/0xffffffff || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m owner --uid-owner 0-1 -p udp " +
                "-j MARK --set-xmark $groupHex/0xffffffff || fail tproxy6",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m mark --mark $prefixHex/$maskHex -j RETURN || fail tproxy6",
            "ip -6 route replace local ::/0 dev lo table $table || fail route6",
            "ip -6 rule add fwmark $prefixHex/$maskHex table $table pref $priority || fail route6",
            "ip6tables -t mangle -I PREROUTING 1 -j ${chains.ipv6Prerouting} || fail tproxy6",
            "ip6tables -t mangle -I OUTPUT 1 -j ${chains.ipv6Output} || fail tproxy6",
            "ip6tables -t mangle -C PREROUTING -j ${chains.ipv6Prerouting} || fail tproxy6",
            "ip6tables -t mangle -C OUTPUT -j ${chains.ipv6Output} || fail tproxy6",
            "ip6tables -t mangle -R ${chains.ipv6Output} 1 -j RETURN || fail tproxy6",
            "ip -6 route get 2001:db8::1 mark $groupHex | grep -q 'dev lo' || fail route6",
        )

        private fun ipv6BlockingProbeCommands(chains: ProbeChains): List<String> = listOf(
            "ip6tables -t mangle -N ${chains.ipv6Output} || fail ipv6block",
            "ip6tables -t mangle -A ${chains.ipv6Output} -j RETURN || fail ipv6block",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m owner --uid-owner 0-1 -j RETURN || fail ipv6block",
            "ip6tables -t mangle -A ${chains.ipv6Output} -m owner --uid-owner 0-1 -j DROP || fail ipv6block",
            "ip6tables -t mangle -I OUTPUT 1 -j ${chains.ipv6Output} || fail ipv6block",
            "ip6tables -t mangle -C OUTPUT -j ${chains.ipv6Output} || fail ipv6block",
            "ip6tables -t mangle -R ${chains.ipv6Output} 1 -j RETURN || fail ipv6block",
            "ip6tables -t filter -N ${chains.ipv6Filter} || fail ipv6block",
            "ip6tables -t filter -A ${chains.ipv6Filter} -j RETURN || fail ipv6block",
            "ip6tables -t filter -A ${chains.ipv6Filter} -m owner --uid-owner 0-1 -j RETURN || fail ipv6block",
            "ip6tables -t filter -A ${chains.ipv6Filter} -m owner --uid-owner 0-1 " +
                "-j REJECT --reject-with icmp6-no-route || fail ipv6block",
            "ip6tables -t filter -I OUTPUT 1 -j ${chains.ipv6Filter} || fail ipv6block",
            "ip6tables -t filter -C OUTPUT -j ${chains.ipv6Filter} || fail ipv6block",
            "ip6tables -t filter -R ${chains.ipv6Filter} 1 -j RETURN || fail ipv6block",
        )

        private data class ProbeChains(
            val ipv4Prerouting: String,
            val ipv4Output: String,
            val ipv6Prerouting: String,
            val ipv6Output: String,
            val ipv6Filter: String,
        )

        private data class ProbeFirewallResource(
            val tool: String,
            val hook: String,
            val chain: String,
        )
    }
}

internal data class FwmarkRule(
    val priority: Int,
    val value: UInt,
    val mask: UInt,
)

internal fun overlappingFwmarkRules(output: String, value: Int, mask: Int): List<FwmarkRule> {
    require(mask and 0xff == 0) { "TPROXY group bits must remain outside the prefix mask" }
    val targetValue = value.toUInt()
    return output.lineSequence().mapNotNull { line ->
        val priority = line.substringBefore(':').trim().toIntOrNull() ?: return@mapNotNull null
        val encoded = Regex("\\bfwmark\\s+(0x[0-9a-fA-F]+|[0-9]+)(?:/(0x[0-9a-fA-F]+|[0-9]+))?")
            .find(line)
            ?: return@mapNotNull null
        val ruleValue = encoded.groupValues[1].parseUInt() ?: return@mapNotNull null
        val ruleMask = encoded.groupValues[2].takeIf(String::isNotEmpty)?.parseUInt() ?: UInt.MAX_VALUE
        FwmarkRule(priority, ruleValue, ruleMask).takeIf {
            (1u..255u).any { group ->
                val generatedMark = targetValue or group
                (generatedMark and ruleMask) == (ruleValue and ruleMask)
            }
        }
    }.toList()
}

private fun String.parseUInt(): UInt? = if (startsWith("0x", ignoreCase = true)) {
    drop(2).toUIntOrNull(16)
} else {
    toUIntOrNull()
}

internal fun resolveDualStackCompatibility(
    ipv4: TproxyCompatibility.Supported,
    dualStack: TproxyCompatibility,
): TproxyCompatibility = if (
    dualStack is TproxyCompatibility.Unsupported &&
    dualStack.reason == TproxyCompatibility.Reason.TproxyIpv6Unavailable
) {
    ipv4.copy(ipv6 = false)
} else {
    dualStack
}
