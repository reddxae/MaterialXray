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
        val socketMatchOptimization: Boolean,
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
            return TproxyCompatibility.Supported(
                ipv6 = allowIpv6,
                socketMatchOptimization = "socket=1" in result.output,
            )
        }

        val stage = result.output.lineSequence()
            .lastOrNull { it.startsWith("stage=") }
            ?.substringAfter('=')
        val reason = when {
            result.exitCode == -1 && "timed out" in result.error -> TproxyCompatibility.Reason.CommandTimedOut
            stage == "iptables" -> TproxyCompatibility.Reason.IptablesMangleUnavailable
            stage == "owner" -> TproxyCompatibility.Reason.OwnerMatchUnavailable
            stage == "mark" -> TproxyCompatibility.Reason.MarkTargetUnavailable
            stage == "tproxy4" -> TproxyCompatibility.Reason.TproxyIpv4Unavailable
            stage == "route4" -> TproxyCompatibility.Reason.PolicyRoutingUnavailable
            stage == "tproxy6" || stage == "route6" -> TproxyCompatibility.Reason.TproxyIpv6Unavailable
            stage == "conflict" -> TproxyCompatibility.Reason.RouteTableConflict
            stage == "cleanup" -> TproxyCompatibility.Reason.ProbeCleanupFailed
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
            val chain = "MXP$suffix".take(28)
            val table = 19_000 + suffix.takeLast(3).toInt(16) % 500
            val priority = PROBE_PRIORITY_BASE + suffix.takeLast(3).toInt(16) % PROBE_PRIORITY_SLOTS
            val markHex = "0x${PROBE_MARK.toString(16)}"
            val maskHex = "0x${PROBE_MASK.toString(16)}"
            val ipv6Probe = if (allowIpv6) {
                "ip6tables -t mangle -N $chain || fail tproxy6; " +
                    "ip6tables -t mangle -A $chain -p tcp -j TPROXY --on-port 9 --tproxy-mark $markHex/$maskHex || fail tproxy6; " +
                    "ip6tables -t mangle -A $chain -p udp -j TPROXY --on-port 9 --tproxy-mark $markHex/$maskHex || fail tproxy6; " +
                    "ip -6 route add local ::/0 dev lo table $table || fail route6; " +
                    "ip -6 rule add fwmark $markHex/$maskHex table $table pref $priority || fail route6; " +
                    "ip -6 route get 2001:db8::1 mark $markHex | grep -q 'dev lo' || fail route6; "
            } else {
                ""
            }
            val ipv6Cleanup = if (allowIpv6) {
                "ip -6 rule del fwmark $markHex/$maskHex table $table pref $priority 2>/dev/null || true; " +
                    "ip -6 route del local ::/0 dev lo table $table 2>/dev/null || true; " +
                    "ip6tables -t mangle -F $chain 2>/dev/null || true; " +
                    "ip6tables -t mangle -X $chain 2>/dev/null || true; "
            } else {
                ""
            }
            val ipv6Verify = if (allowIpv6) {
                "ip6tables -t mangle -S $chain >/dev/null 2>&1 && fail cleanup; " +
                    "ip -6 rule show | grep -q 'pref $priority.*lookup $table' && fail cleanup; "
            } else {
                ""
            }
            return "fail() { printf 'stage=%s\\n' \"\$1\"; cleanup; exit 1; }; " +
                "cleanup() { " +
                "ip rule del fwmark $markHex/$maskHex table $table pref $priority 2>/dev/null || true; " +
                "ip route del local 0.0.0.0/0 dev lo table $table 2>/dev/null || true; " +
                "iptables -t mangle -F $chain 2>/dev/null || true; " +
                "iptables -t mangle -X $chain 2>/dev/null || true; " + ipv6Cleanup + "}; " +
                "if iptables -t mangle -S $chain >/dev/null 2>&1 || " +
                "ip6tables -t mangle -S $chain >/dev/null 2>&1 || " +
                "[ -n \"\$(ip rule show pref $priority 2>/dev/null)\" ] || " +
                "[ -n \"\$(ip -6 rule show pref $priority 2>/dev/null)\" ] || " +
                "[ -n \"\$(ip route show table $table 2>/dev/null)\" ] || " +
                "[ -n \"\$(ip -6 route show table $table 2>/dev/null)\" ]; then " +
                "printf 'stage=conflict\\n'; exit 43; fi; " +
                "iptables -t mangle -N $chain || fail iptables; " +
                "iptables -t mangle -A $chain -m owner --uid-owner 0 -j RETURN || fail owner; " +
                "iptables -t mangle -A $chain -j MARK --set-xmark $markHex/$maskHex || fail mark; " +
                "iptables -t mangle -A $chain -p tcp -j TPROXY --on-port 9 --tproxy-mark $markHex/$maskHex || fail tproxy4; " +
                "iptables -t mangle -A $chain -p udp -j TPROXY --on-port 9 --tproxy-mark $markHex/$maskHex || fail tproxy4; " +
                "ip route add local 0.0.0.0/0 dev lo table $table || fail route4; " +
                "ip rule add fwmark $markHex/$maskHex table $table pref $priority || fail route4; " +
                "ip route get 192.0.2.1 mark $markHex | grep -q 'dev lo' || fail route4; " +
                ipv6Probe +
                "if iptables -t mangle -A $chain -p tcp -m socket --transparent -j RETURN 2>/dev/null; then " +
                "printf 'socket=1\\n'; else printf 'socket=0\\n'; fi; " +
                "cleanup; " +
                "iptables -t mangle -S $chain >/dev/null 2>&1 && fail cleanup; " +
                "ip rule show | grep -q 'pref $priority.*lookup $table' && fail cleanup; " +
                ipv6Verify +
                "true"
        }
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
