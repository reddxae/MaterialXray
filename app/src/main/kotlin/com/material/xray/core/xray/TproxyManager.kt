package com.material.xray.core.xray

import com.material.xray.core.app.appUidRangeForProfile
import com.material.xray.core.app.isApplicationUid
import com.material.xray.core.root.RootShell

data class TproxyTrafficGroup(
    val state: TproxyGroupState,
    val uids: Set<Int>,
    val isBase: Boolean = false,
)

data class TproxyTrafficPlan(
    val runtimeState: TproxyRuntimeState,
    val groups: List<TproxyTrafficGroup>,
    val bypassUids: Set<Int>,
    val routeProfileIds: Set<Int>,
    val outboundMark: Int,
)

class TproxyManager internal constructor(
    private val appUid: Int,
    private val executeCommand: suspend (String) -> RootShell.Result,
) {
    constructor(shell: RootShell, appUid: Int) : this(appUid, { command -> shell.execute(command) })

    suspend fun installGuard(plan: TproxyTrafficPlan): TunManager.RoutingResult = execute(
        guardInstallCommand(plan, appUid),
        "TPROXY startup guard setup",
    )

    suspend fun activate(plan: TproxyTrafficPlan): TunManager.RoutingResult {
        val state = plan.runtimeState
        val rules = executeCommand("ip rule show; ip -6 rule show")
        if (!rules.isSuccess) return rules.toRoutingResult("TPROXY policy rule inspection")
        if (
            overlappingFwmarkRules(rules.output, state.markPrefix, state.markMask).any {
                it.priority <= state.rulePriority
            }
        ) {
            return TunManager.RoutingResult(success = false, error = "TPROXY packet-mark namespace conflicts with an existing rule")
        }
        val routes = executeCommand(
            "ip route show table ${state.routeTable}; ip -6 route show table ${state.routeTable}",
        )
        if (!routes.isSuccess) return routes.toRoutingResult("TPROXY route table inspection")
        if (routes.output.isNotBlank()) {
            return TunManager.RoutingResult(success = false, error = "TPROXY route table ${state.routeTable} is already in use")
        }
        return execute(activationCommand(plan, appUid), "TPROXY routing setup")
    }

    suspend fun update(plan: TproxyTrafficPlan, currentSlot: String): TunManager.RoutingResult {
        val nextSlot = if (currentSlot == SLOT_A) SLOT_B else SLOT_A
        return execute(updateCommand(plan, appUid, currentSlot, nextSlot), "TPROXY app routing update")
    }

    suspend fun verify(state: TproxyRuntimeState): Boolean = executeCommand(verifyCommand(state, appUid)).isSuccess

    suspend fun remove(state: TproxyRuntimeState?, preserveGuard: Boolean = false): Boolean = executeCommand(cleanupCommand(state, appUid, preserveGuard)).isSuccess

    suspend fun removeGuard(): Boolean = executeCommand(guardCleanupCommand(appUid)).isSuccess

    suspend fun hasGuard(): Boolean = executeCommand(guardVerifyCommand(appUid)).isSuccess

    private suspend fun execute(command: String, label: String): TunManager.RoutingResult {
        val result = executeCommand(command)
        if (result.isSuccess) return TunManager.RoutingResult(success = true)
        return result.toRoutingResult(label)
    }

    private fun RootShell.Result.toRoutingResult(label: String): TunManager.RoutingResult {
        val details = listOf(output.trim(), error.trim()).filter(String::isNotEmpty).joinToString(" | ")
        return TunManager.RoutingResult(
            success = false,
            error = if (details.isEmpty()) "$label failed (exit=$exitCode)" else "$label failed: $details",
        )
    }

    internal companion object {
        const val SLOT_A = "a"
        const val SLOT_B = "b"
        const val RULE_PRIORITY = 11_990

        fun createRuntimeState(
            routeTable: Int,
            groups: List<Pair<Long, String>>,
            ports: List<Int>,
            allowIpv6: Boolean,
        ): TproxyRuntimeState {
            require(groups.isNotEmpty())
            require(groups.size == ports.size)
            require(routeTable in 1..32_765)
            require(ports.all { it in 1..65_535 } && ports.distinct().size == ports.size)
            require(groups.size <= 255)
            return TproxyRuntimeState(
                markPrefix = TproxyCompatibilityDetector.MARK_PREFIX,
                markMask = TproxyCompatibilityDetector.MARK_MASK,
                routeTable = routeTable,
                rulePriority = RULE_PRIORITY,
                outputChainSlot = SLOT_A,
                groups = groups.mapIndexed { index, (routeKey, inboundTag) ->
                    TproxyGroupState(
                        routeKey = routeKey,
                        mark = TproxyCompatibilityDetector.MARK_PREFIX or (index + 1),
                        port = ports[index],
                        inboundTag = inboundTag,
                    )
                },
                ipv6Enabled = allowIpv6,
            )
        }

        fun activationCommand(plan: TproxyTrafficPlan, appUid: Int): String {
            validatePlan(plan, appUid)
            val state = plan.runtimeState
            val names = chainNames(appUid)
            val markPrefix = hex(state.markPrefix)
            val markMask = hex(state.markMask)
            val commands = mutableListOf<String>()
            commands += "ip route replace local 0.0.0.0/0 dev lo table ${state.routeTable}"
            commands += "ip rule add fwmark $markPrefix/$markMask table ${state.routeTable} pref ${state.rulePriority}"
            if (state.ipv6Enabled) {
                commands += "ip -6 route replace local ::/0 dev lo table ${state.routeTable}"
                commands += "ip -6 rule add fwmark $markPrefix/$markMask table ${state.routeTable} pref ${state.rulePriority}"
            }
            commands += buildPreroutingCommands("iptables", names.prerouting, plan)
            commands += buildOutputActivationCommands("iptables", names, plan, appUid, SLOT_A)
            if (state.ipv6Enabled) {
                commands += buildPreroutingCommands("ip6tables", names.prerouting, plan)
                commands += buildOutputActivationCommands("ip6tables", names, plan, appUid, SLOT_A)
            } else {
                commands += buildIpv6RejectActivationCommands(names, plan, appUid, SLOT_A)
            }
            commands += removeGuardCommands("iptables", names.guard)
            commands += removeGuardCommands("ip6tables", names.guard)
            return commands.joinToString(" && ")
        }

        fun guardInstallCommand(plan: TproxyTrafficPlan, appUid: Int): String {
            validatePlan(plan, appUid)
            val names = chainNames(appUid)
            val commands = buildGuardCommands("iptables", names.guard, plan, appUid).toMutableList()
            commands += buildGuardCommands("ip6tables", names.guard, plan, appUid)
            return commands.joinToString(" && ")
        }

        fun updateCommand(
            plan: TproxyTrafficPlan,
            appUid: Int,
            currentSlot: String,
            nextSlot: String,
        ): String {
            validatePlan(plan, appUid)
            require(currentSlot in setOf(SLOT_A, SLOT_B) && nextSlot in setOf(SLOT_A, SLOT_B) && currentSlot != nextSlot)
            val names = chainNames(appUid)
            val commands = buildOutputUpdateCommands("iptables", names, plan, appUid, currentSlot, nextSlot).toMutableList()
            if (plan.runtimeState.ipv6Enabled) {
                commands += buildOutputUpdateCommands("ip6tables", names, plan, appUid, currentSlot, nextSlot)
            } else {
                commands += buildIpv6RejectUpdateCommands(names, plan, appUid, currentSlot, nextSlot)
            }
            return commands.joinToString(" && ")
        }

        fun verifyCommand(state: TproxyRuntimeState, appUid: Int): String {
            val names = chainNames(appUid)
            val prefix = hex(state.markPrefix)
            val mask = hex(state.markMask)
            val commands = mutableListOf(
                "iptables -t mangle -C OUTPUT -j ${names.output}",
                "iptables -t mangle -C ${names.output} -j ${names.slot(state.outputChainSlot)}",
                "iptables -t mangle -C PREROUTING -j ${names.prerouting}",
                "ip rule show | grep -q 'fwmark $prefix/$mask.*lookup ${state.routeTable}'",
                "ip route show table ${state.routeTable} | grep -q '^local .* dev lo'",
            )
            state.groups.forEach { group ->
                val mark = hex(group.mark)
                commands += "iptables -t mangle -S ${names.slot(state.outputChainSlot)} | " +
                    "grep -q -- '--set-xmark $mark/0xffffffff'"
                for (protocol in listOf("tcp", "udp")) {
                    commands += "iptables -t mangle -C ${names.prerouting} -p $protocol -m mark " +
                        "--mark $mark/0xffffffff -j TPROXY --on-port ${group.port} --tproxy-mark $mark/0xffffffff"
                    commands += "iptables -t mangle -C ${names.slot(state.outputChainSlot)} -m addrtype --dst-type LOCAL " +
                        "-p $protocol --dport ${group.port} -j DROP"
                }
                commands += "ss -lnt | grep -Eq '[:.]${group.port}([^0-9]|$)'"
                commands += "ss -lnu | grep -Eq '[:.]${group.port}([^0-9]|$)'"
            }
            if (state.ipv6Enabled) {
                commands += "ip6tables -t mangle -C OUTPUT -j ${names.output}"
                commands += "ip6tables -t mangle -C ${names.output} -j ${names.slot(state.outputChainSlot)}"
                commands += "ip6tables -t mangle -C PREROUTING -j ${names.prerouting}"
                commands += "ip -6 rule show | grep -q 'fwmark $prefix/$mask.*lookup ${state.routeTable}'"
                commands += "ip -6 route show table ${state.routeTable} | grep -q '^local .* dev lo'"
                state.groups.forEach { group ->
                    val mark = hex(group.mark)
                    commands += "ip6tables -t mangle -S ${names.slot(state.outputChainSlot)} | " +
                        "grep -q -- '--set-xmark $mark/0xffffffff'"
                    for (protocol in listOf("tcp", "udp")) {
                        commands += "ip6tables -t mangle -C ${names.prerouting} -p $protocol -m mark " +
                            "--mark $mark/0xffffffff -j TPROXY --on-port ${group.port} --tproxy-mark $mark/0xffffffff"
                        commands += "ip6tables -t mangle -C ${names.slot(state.outputChainSlot)} " +
                            "-m addrtype --dst-type LOCAL -p $protocol --dport ${group.port} -j DROP"
                    }
                }
            } else {
                commands += "ip6tables -t filter -C OUTPUT -j ${names.output}"
                commands += "ip6tables -t filter -C ${names.output} -j ${names.slot(state.outputChainSlot)}"
                commands += "ip6tables -t filter -S ${names.slot(state.outputChainSlot)} | " +
                    "grep -q -- '--reject-with icmp6-no-route'"
            }
            return commands.joinToString(" && ")
        }

        fun cleanupCommand(state: TproxyRuntimeState?, appUid: Int, preserveGuard: Boolean = false): String {
            require(appUid > 0)
            val names = chainNames(appUid)
            val table = state?.routeTable
            val priority = state?.rulePriority ?: RULE_PRIORITY
            val prefix = hex(state?.markPrefix ?: TproxyCompatibilityDetector.MARK_PREFIX)
            val mask = hex(state?.markMask ?: TproxyCompatibilityDetector.MARK_MASK)
            val commands = mutableListOf<String>()
            for (tool in listOf("iptables", "ip6tables")) {
                if (!preserveGuard) commands += "$tool -t mangle -D OUTPUT -j ${names.guard} 2>/dev/null || true"
                commands += "$tool -t mangle -D OUTPUT -j ${names.output} 2>/dev/null || true"
                commands += "$tool -t mangle -D PREROUTING -j ${names.prerouting} 2>/dev/null || true"
                val chains = listOf(names.output, names.slotA, names.slotB, names.prerouting) +
                    names.guard.takeUnless { preserveGuard }
                for (chain in chains.filterNotNull()) {
                    commands += "$tool -t mangle -F $chain 2>/dev/null || true"
                    commands += "$tool -t mangle -X $chain 2>/dev/null || true"
                }
            }
            commands += "ip6tables -t filter -D OUTPUT -j ${names.output} 2>/dev/null || true"
            for (chain in listOf(names.output, names.slotA, names.slotB)) {
                commands += "ip6tables -t filter -F $chain 2>/dev/null || true"
                commands += "ip6tables -t filter -X $chain 2>/dev/null || true"
            }
            commands += discoveredRouteTableCleanupCommand("ip", prefix, mask, priority)
            commands += discoveredRouteTableCleanupCommand("ip -6", prefix, mask, priority)
            commands += "while ip rule del fwmark $prefix/$mask pref $priority 2>/dev/null; do :; done"
            commands += "while ip -6 rule del fwmark $prefix/$mask pref $priority 2>/dev/null; do :; done"
            table?.let {
                commands += "ip route del local 0.0.0.0/0 dev lo table $it 2>/dev/null || true"
                commands += "ip -6 route del local ::/0 dev lo table $it 2>/dev/null || true"
                commands += "ip -6 route del unreachable default table $it 2>/dev/null || true"
            }
            commands += "! iptables -t mangle -C OUTPUT -j ${names.output} 2>/dev/null"
            commands += "! iptables -t mangle -C PREROUTING -j ${names.prerouting} 2>/dev/null"
            return commands.joinToString("; ")
        }

        fun guardCleanupCommand(appUid: Int): String {
            require(appUid > 0)
            val guard = chainNames(appUid).guard
            return listOf("iptables", "ip6tables").flatMap { tool ->
                listOf(
                    "$tool -t mangle -D OUTPUT -j $guard 2>/dev/null || true",
                    "$tool -t mangle -F $guard 2>/dev/null || true",
                    "$tool -t mangle -X $guard 2>/dev/null || true",
                    "! $tool -t mangle -C OUTPUT -j $guard 2>/dev/null",
                )
            }.joinToString("; ")
        }

        fun guardVerifyCommand(appUid: Int): String {
            require(appUid > 0)
            val guard = chainNames(appUid).guard
            return "iptables -t mangle -C OUTPUT -j $guard"
        }

        private fun discoveredRouteTableCleanupCommand(
            ipCommand: String,
            prefix: String,
            mask: String,
            priority: Int,
        ): String = "tables=\$($ipCommand rule show 2>/dev/null | while IFS= read -r line; do " +
            "case \"\$line\" in \"$priority:\"*\"fwmark $prefix/$mask\"*) " +
            "previous=''; for field in \$line; do " +
            "[ \"\$previous\" = lookup ] && printf '%s\\n' \"\$field\"; previous=\$field; done;; esac; done); " +
            "for table in \$tables; do case \"\$table\" in ''|*[!0-9]*) continue;; esac; " +
            "$ipCommand route del local ${if (ipCommand == "ip -6") "::/0" else "0.0.0.0/0"} " +
            "dev lo table \"\$table\" 2>/dev/null || true; done"

        private fun buildGuardCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> {
            val setup = buildList {
                add("$tool -t mangle -N $chain")
                add("$tool -t mangle -A $chain -m owner --uid-owner $appUid -j RETURN")
                uidRanges(plan.bypassUids).forEach { range ->
                    add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j RETURN")
                }
                plan.routeProfileIds.toSortedSet().forEach { profileId ->
                    val range = appUidRangeForProfile(profileId)
                    add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j DROP")
                }
                add("$tool -t mangle -I OUTPUT 1 -j $chain")
            }.joinToString(" && ")
            return listOf("if $tool -t mangle -C OUTPUT -j $chain 2>/dev/null; then true; else $setup; fi")
        }

        private fun buildPreroutingCommands(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
        ): List<String> = buildList {
            add("$tool -t mangle -N $chain")
            plan.groups.forEach { group ->
                val mark = hex(group.state.mark)
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $chain -p $protocol -m mark --mark $mark/0xffffffff " +
                            "-j TPROXY --on-port ${group.state.port} --tproxy-mark $mark/0xffffffff",
                    )
                }
            }
            add("$tool -t mangle -I PREROUTING 1 -j $chain")
        }

        private fun buildOutputActivationCommands(
            tool: String,
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            slot: String,
        ): List<String> {
            val slotChain = names.slot(slot)
            return buildList {
                add("$tool -t mangle -N $slotChain")
                addAll(outputRules(tool, slotChain, plan, appUid))
                add("$tool -t mangle -N ${names.output}")
                add("$tool -t mangle -A ${names.output} -j $slotChain")
                add("$tool -t mangle -I OUTPUT 1 -j ${names.output}")
            }
        }

        private fun buildOutputUpdateCommands(
            tool: String,
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            currentSlot: String,
            nextSlot: String,
        ): List<String> {
            val currentChain = names.slot(currentSlot)
            val nextChain = names.slot(nextSlot)
            return buildList {
                add("$tool -t mangle -F $nextChain 2>/dev/null || true")
                add("$tool -t mangle -X $nextChain 2>/dev/null || true")
                add("$tool -t mangle -N $nextChain")
                addAll(outputRules(tool, nextChain, plan, appUid))
                add("$tool -t mangle -R ${names.output} 1 -j $nextChain")
                add("$tool -t mangle -F $currentChain")
                add("$tool -t mangle -X $currentChain")
            }
        }

        private fun buildIpv6RejectActivationCommands(
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            slot: String,
        ): List<String> {
            val slotChain = names.slot(slot)
            return buildList {
                add("ip6tables -t filter -N $slotChain")
                addAll(ipv6RejectRules(slotChain, plan, appUid))
                add("ip6tables -t filter -N ${names.output}")
                add("ip6tables -t filter -A ${names.output} -j $slotChain")
                add("ip6tables -t filter -I OUTPUT 1 -j ${names.output}")
            }
        }

        private fun buildIpv6RejectUpdateCommands(
            names: ChainNames,
            plan: TproxyTrafficPlan,
            appUid: Int,
            currentSlot: String,
            nextSlot: String,
        ): List<String> {
            val currentChain = names.slot(currentSlot)
            val nextChain = names.slot(nextSlot)
            return buildList {
                add("ip6tables -t filter -F $nextChain 2>/dev/null || true")
                add("ip6tables -t filter -X $nextChain 2>/dev/null || true")
                add("ip6tables -t filter -N $nextChain")
                addAll(ipv6RejectRules(nextChain, plan, appUid))
                add("ip6tables -t filter -R ${names.output} 1 -j $nextChain")
                add("ip6tables -t filter -F $currentChain")
                add("ip6tables -t filter -X $currentChain")
            }
        }

        private fun ipv6RejectRules(
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            add("ip6tables -t filter -A $chain -m owner --uid-owner $appUid -j RETURN")
            uidRanges(plan.bypassUids).forEach { range ->
                add("ip6tables -t filter -A $chain -m owner --uid-owner ${range.asArgument()} -j RETURN")
            }
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                add(
                    "ip6tables -t filter -A $chain -m owner --uid-owner ${appUidRangeForProfile(profileId).asArgument()} " +
                        "-j REJECT --reject-with icmp6-no-route",
                )
            }
        }

        private fun outputRules(
            tool: String,
            chain: String,
            plan: TproxyTrafficPlan,
            appUid: Int,
        ): List<String> = buildList {
            val state = plan.runtimeState
            val prefix = hex(state.markPrefix)
            val mask = hex(state.markMask)
            add("$tool -t mangle -A $chain -m mark --mark ${plan.outboundMark}/0xffffffff -j RETURN")
            add("$tool -t mangle -A $chain -m owner --uid-owner $appUid -j RETURN")
            state.groups.forEach { group ->
                for (protocol in listOf("tcp", "udp")) {
                    add(
                        "$tool -t mangle -A $chain -m addrtype --dst-type LOCAL -p $protocol " +
                            "--dport ${group.port} -j DROP",
                    )
                }
            }
            val loopback = if (tool == "ip6tables") "::1/128" else "127.0.0.0/8"
            val multicast = if (tool == "ip6tables") "ff00::/8" else "224.0.0.0/4"
            add("$tool -t mangle -A $chain -d $loopback -j RETURN")
            add("$tool -t mangle -A $chain -d $multicast -j RETURN")
            if (tool == "iptables") add("$tool -t mangle -A $chain -d 255.255.255.255/32 -j RETURN")
            uidRanges(plan.bypassUids).forEach { range ->
                add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j RETURN")
            }
            plan.groups.filterNot { it.isBase }.forEach { group ->
                uidRanges(group.uids).forEach { range ->
                    addMarkRules(tool, chain, range, group.state.mark)
                }
            }
            add("$tool -t mangle -A $chain -m mark --mark $prefix/$mask -j RETURN")
            val base = plan.groups.single { it.isBase }
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                addMarkRules(tool, chain, appUidRangeForProfile(profileId), base.state.mark)
            }
            add("$tool -t mangle -A $chain -m mark --mark $prefix/$mask -j RETURN")
            plan.routeProfileIds.toSortedSet().forEach { profileId ->
                val range = appUidRangeForProfile(profileId)
                add("$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -j DROP")
            }
        }

        private fun MutableList<String>.addMarkRules(
            tool: String,
            chain: String,
            range: IntRange,
            mark: Int,
        ) {
            val markHex = hex(mark)
            for (protocol in listOf("tcp", "udp")) {
                add(
                    "$tool -t mangle -A $chain -m owner --uid-owner ${range.asArgument()} -p $protocol " +
                        "-j MARK --set-xmark $markHex/0xffffffff",
                )
            }
        }

        private fun removeGuardCommands(tool: String, chain: String): List<String> = listOf(
            "$tool -t mangle -D OUTPUT -j $chain 2>/dev/null || true",
            "$tool -t mangle -F $chain 2>/dev/null || true",
            "$tool -t mangle -X $chain 2>/dev/null || true",
        )

        private fun uidRanges(uids: Set<Int>): List<IntRange> {
            val sorted = uids.filter(::isApplicationUid).toSortedSet()
            if (sorted.isEmpty()) return emptyList()
            val ranges = mutableListOf<IntRange>()
            var start = sorted.first()
            var previous = start
            sorted.drop(1).forEach { uid ->
                if (uid == previous + 1) {
                    previous = uid
                } else {
                    ranges += start..previous
                    start = uid
                    previous = uid
                }
            }
            ranges += start..previous
            return ranges
        }

        private fun validatePlan(plan: TproxyTrafficPlan, appUid: Int) {
            require(appUid > 0)
            require(plan.groups.isNotEmpty() && plan.groups.count { it.isBase } == 1)
            require(plan.groups.map { it.state } == plan.runtimeState.groups)
            require(plan.routeProfileIds.all { it >= 0 })
            require(plan.outboundMark >= 0)
        }

        private fun IntRange.asArgument(): String = if (first == last) first.toString() else "$first-$last"

        private fun hex(value: Int): String = "0x${value.toUInt().toString(16)}"

        private fun chainNames(appUid: Int): ChainNames {
            require(appUid > 0)
            val suffix = appUid.toString(16)
            return ChainNames(
                guard = "MXG$suffix",
                output = "MXO$suffix",
                slotA = "MXOA$suffix",
                slotB = "MXOB$suffix",
                prerouting = "MXP$suffix",
            )
        }

        private data class ChainNames(
            val guard: String,
            val output: String,
            val slotA: String,
            val slotB: String,
            val prerouting: String,
        ) {
            fun slot(value: String): String = if (value == SLOT_A) slotA else slotB
        }
    }
}
