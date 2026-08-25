package com.material.xray.core.xray

import com.material.xray.core.app.appUidRangeForProfile
import com.material.xray.core.app.isApplicationUid
import com.material.xray.core.app.profileIdForUid
import com.material.xray.core.root.RootShell

class TunManager internal constructor(
    private val executeCommand: suspend (String) -> RootShell.Result,
) {
    constructor(shell: RootShell) : this(executeCommand = { command -> shell.execute(command) })

    data class PhysicalRoute(
        val dev: String,
        val gateway: String?,
        val table: String?,
    )

    data class RoutingResult(
        val success: Boolean,
        val error: String? = null,
    )

    data class TunSetupResult(
        val success: Boolean,
        val processExited: Boolean = false,
        val error: String? = null,
    )

    data class AppTunRoute(
        val tunName: String,
        val routeTable: Int,
        val uids: Set<Int>,
    )

    suspend fun findAvailableWlanName(): String? {
        val result = executeCommand("ip -o link show 2>/dev/null")
        if (!result.isSuccess) return null

        val interfaceNames = result.output.lineSequence().mapNotNull(::parseLinkInterfaceName)
        return nextAvailableWlanName(interfaceNames)
    }

    suspend fun configureTun(
        tunName: String,
        addressCidr: String = DEFAULT_TUN_ADDRESS_CIDR,
        ipv6AddressCidr: String? = null,
        processId: Int? = null,
        isProcessAlive: suspend () -> Boolean = { true },
    ): TunSetupResult {
        val quotedTunName = shellQuote(tunName)
        val upCommand = buildList {
            ipv6AddressCidr?.let {
                val disableIpv6Path = shellQuote("/proc/sys/net/ipv6/conf/$tunName/disable_ipv6")
                add("{ [ ! -e $disableIpv6Path ] || echo 0 > $disableIpv6Path; } 2>/dev/null || true")
            }
            add("ip addr replace ${shellQuote(addressCidr)} dev $quotedTunName")
            ipv6AddressCidr?.let {
                add("ip -6 addr replace ${shellQuote(it)} dev $quotedTunName nodad")
            }
            add("ip link set $quotedTunName up")
        }.joinToString(" && ")
        if (processId != null) {
            val setupCommand = "i=0; while [ \$i -lt $TUN_WAIT_ATTEMPTS ]; do " +
                "kill -0 $processId 2>/dev/null || exit $TUN_PROCESS_EXIT_CODE; " +
                "if ip link show $quotedTunName >/dev/null 2>&1; then $upCommand; exit \$?; fi; " +
                "sleep ${TUN_WAIT_POLL_INTERVAL_MS / 1_000.0}; i=\$((i + 1)); done; " +
                "exit $TUN_WAIT_TIMEOUT_EXIT_CODE"
            val result = executeCommand(setupCommand)
            when {
                result.isSuccess -> Unit
                result.exitCode == TUN_PROCESS_EXIT_CODE -> return TunSetupResult(success = false, processExited = true)
                result.exitCode == TUN_WAIT_TIMEOUT_EXIT_CODE -> {
                    return if (isProcessAlive()) {
                        TunSetupResult(success = false, error = "TUN interface $tunName did not come up within timeout")
                    } else {
                        TunSetupResult(success = false, processExited = true)
                    }
                }
                else -> return TunSetupResult(success = false, error = result.toCommandError(upCommand))
            }
        } else {
            val waitResult = waitForTunInChunks(quotedTunName, upCommand, isProcessAlive)
            if (waitResult != null) return waitResult
        }

        if (ipv6AddressCidr != null) {
            val inspectCommand = "ip -6 addr show dev $quotedTunName"
            val inspectResult = executeCommand(inspectCommand)
            val configured = inspectResult.output.lineSequence().any { line ->
                val normalized = line.trim()
                normalized.startsWith("inet6 $ipv6AddressCidr ") &&
                    "tentative" !in normalized &&
                    "dadfailed" !in normalized
            }
            if (!inspectResult.isSuccess || !configured) {
                val detail = inspectResult.toCommandError(inspectCommand)
                return TunSetupResult(
                    success = false,
                    error = "IPv6 address $ipv6AddressCidr was not configured on $tunName: $detail",
                )
            }
        }

        return TunSetupResult(success = true)
    }

    private suspend fun waitForTunInChunks(
        quotedTunName: String,
        upCommand: String,
        isProcessAlive: suspend () -> Boolean,
    ): TunSetupResult? {
        var configured = false
        repeat(TUN_WAIT_ATTEMPTS / TUN_WAIT_CHUNK_ATTEMPTS) {
            if (configured) return@repeat
            val setupCommand = "i=0; while [ \$i -lt $TUN_WAIT_CHUNK_ATTEMPTS ]; do " +
                "if ip link show $quotedTunName >/dev/null 2>&1; then $upCommand; exit \$?; fi; " +
                "sleep ${TUN_WAIT_POLL_INTERVAL_MS / 1_000.0}; i=\$((i + 1)); done; " +
                "exit $TUN_WAIT_TIMEOUT_EXIT_CODE"
            val upResult = executeCommand(setupCommand)
            if (upResult.isSuccess) {
                configured = true
                return@repeat
            }
            if (upResult.exitCode != TUN_WAIT_TIMEOUT_EXIT_CODE) {
                return TunSetupResult(success = false, error = upResult.toCommandError(upCommand))
            }
            if (!isProcessAlive()) return TunSetupResult(success = false, processExited = true)
        }
        if (!configured) {
            return TunSetupResult(success = false, error = "TUN interface $quotedTunName did not come up within timeout")
        }
        return null
    }

    suspend fun detectPhysicalRoute(tunName: String): PhysicalRoute? {
        detectPhysicalRouteFromRouteGet(tunName)?.let { return it }

        val result = executeCommand("ip route show table all 2>/dev/null | grep '^default '")
        return result.output
            .lineSequence()
            .mapNotNull { parseDefaultRoute(it) }
            .sortedWith(compareByDescending<PhysicalRoute> { it.gateway != null }.thenBy { it.dev })
            .firstOrNull { route ->
                !isManagedTunName(route.dev, tunName) &&
                    !route.dev.startsWith("tun") &&
                    !route.dev.startsWith("xray") &&
                    route.dev != "dummy0"
            }
    }

    private suspend fun detectPhysicalRouteFromRouteGet(tunName: String): PhysicalRoute? {
        val result = executeCommand("ip route get 1.1.1.1 2>/dev/null")
        if (!result.isSuccess) return null

        return result.output
            .lineSequence()
            .mapNotNull { parseDefaultRoute(it) }
            .firstOrNull { route ->
                !isManagedTunName(route.dev, tunName) &&
                    !route.dev.startsWith("tun") &&
                    !route.dev.startsWith("xray") &&
                    route.dev != "dummy0"
            }
    }

    suspend fun applyRouting(
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: PhysicalRoute,
        allowIpv6: Boolean,
        bypassUids: Set<Int>,
        appTunRoutes: List<AppTunRoute> = emptyList(),
        managedAppRouteCount: Int = appTunRoutes.size,
        routeProfileIds: Set<Int> = setOf(0),
        tunnelTetheredClients: Boolean = false,
        bypassLan: Boolean = true,
        cleanExistingState: Boolean = true,
    ): RoutingResult {
        val managedAppTables = appRouteTables(routeTable, managedAppRouteCount)
            .plus(appTunRoutes.map { it.routeTable })
            .distinct()
        val appUids = appTunRoutes.flatMap { it.uids }.toSet()
        val routedProfileIds = (routeProfileIds + (bypassUids + appUids).map(::profileIdForUid))
            .filter { it >= 0 }
            .toSet()
            .ifEmpty { setOf(0) }
        val updateGuardTable = routeTable + UPDATE_GUARD_ROUTE_TABLE_OFFSET

        val guardResult = installRoutingUpdateGuard(updateGuardTable, routedProfileIds, bypassUids)
        if (!guardResult.success) return guardResult

        val uidRoutingCommands = defaultUidRoutingRuleCommands(
            routeTable = routeTable,
            bypassUids = bypassUids + appUids,
            profileIds = routedProfileIds,
        ).toMutableList()
        appTunRoutes.forEach { route ->
            uidRoutingCommands += includedUidRoutingRuleCommands(
                routeTable = route.routeTable,
                uids = route.uids,
                priority = APP_UID_RULE_PRIORITY,
            )
        }
        if (tunnelTetheredClients) {
            uidRoutingCommands += tetherRoutingRuleCommand(routeTable)
        }

        val ipv6UidRoutingCommands = defaultUidRoutingRuleCommands(
            routeTable = routeTable,
            bypassUids = bypassUids + appUids,
            profileIds = routedProfileIds,
            ruleCommand = "ip -6 rule",
        ).toMutableList()
        appTunRoutes.forEach { route ->
            ipv6UidRoutingCommands += includedUidRoutingRuleCommands(
                routeTable = route.routeTable,
                uids = route.uids,
                priority = APP_UID_RULE_PRIORITY,
                ruleCommand = "ip -6 rule",
            )
        }
        if (tunnelTetheredClients) {
            ipv6UidRoutingCommands += tetherRoutingRuleCommand(routeTable, ruleCommand = "ip -6 rule")
        }

        val bypassRoute = physicalBypassRouteCommand(bypassTable, physicalRoute)
        val routeTables = listOf(bypassTable, routeTable) + managedAppTables
        if (cleanExistingState) {
            val setupCommands = buildList {
                add(tetherCleanupCommand())
                add("ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null || true")
                add(removeManagedRoutingTablesCommand(routeTables, "ip rule"))
                add(removeManagedRoutingTablesCommand(routeTables, "ip -6 rule"))
                add(flushRouteTablesCommand(routeTables, "ip route"))
                add(flushRouteTablesCommand(routeTables, "ip -6 route"))
            }.joinToString(" && ")
            val cleanupResult = executeCommand(setupCommands)
            if (!cleanupResult.isSuccess) return cleanupResult.toRoutingError("stale IP routing cleanup")
        }
        val ipv4Setup = buildList {
            add(bypassRoute.removePrefix("ip "))
            add("rule add fwmark $fwmark table $bypassTable prio 10")
            add("route replace default dev $tunName table $routeTable")
            appTunRoutes.forEach { route -> add("route replace default dev ${route.tunName} table ${route.routeTable}") }
            addAll(uidRoutingCommands.map { it.removePrefix("ip ") })
        }
        val ipv6Setup = buildList {
            add(ipv6TunRouteCommand(tunName, routeTable, allowIpv6).removePrefix("ip -6 "))
            appTunRoutes.forEach { route ->
                add(ipv6TunRouteCommand(route.tunName, route.routeTable, allowIpv6).removePrefix("ip -6 "))
            }
            addAll(ipv6UidRoutingCommands.map { it.removePrefix("ip -6 ") })
        }
        val setupCommand = ipBatchCommand(ipv4Setup, ipv6 = false) + " && " + ipBatchCommand(ipv6Setup, ipv6 = true)
        val setupResult = executeCommand(setupCommand)
        if (!setupResult.isSuccess) return setupResult.toRoutingError("IP routing setup")

        if (tunnelTetheredClients) {
            val tetherCommand = tetherSetupCommand(tunName, physicalRoute.dev, allowIpv6, bypassLan)
            val tetherResult = executeCommand(tetherCommand)
            if (!tetherResult.isSuccess) return tetherResult.toRoutingError("tether routing setup")
        }

        return removeRoutingUpdateGuard(updateGuardTable, routedProfileIds, bypassUids)
    }

    suspend fun replacePhysicalBypassRoute(
        bypassTable: Int,
        physicalRoute: PhysicalRoute,
    ): RoutingResult {
        val command = physicalBypassRouteCommand(bypassTable, physicalRoute)
        val result = executeCommand(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError(command)
    }

    suspend fun removeRouting(
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        tunName: String,
        managedAppRouteCount: Int = MAX_APP_TUN_ROUTES,
    ): Boolean = executeCommand(
        routingRemovalCommand(fwmark, routeMark, routeTable, tunName, managedAppRouteCount),
    ).isSuccess

    private fun routingRemovalCommand(
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        tunName: String,
        managedAppRouteCount: Int,
    ): String {
        val bypassTable = routeTable + 1
        val updateGuardTable = routeTable + UPDATE_GUARD_ROUTE_TABLE_OFFSET
        val appTables = appRouteTables(routeTable, managedAppRouteCount)
        val routeTables = listOf(bypassTable, routeTable, updateGuardTable) + appTables
        val interfaceNames = buildList {
            add(tunName)
            for (index in 1..managedAppRouteCount.coerceIn(0, MAX_APP_TUN_ROUTES)) {
                add(appTunName(tunName, index))
            }
        }
        val commands = listOf(
            tetherCleanupCommand(),
            "ip rule del fwmark $fwmark table main prio 10 2>/dev/null || true",
            "ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null || true",
            "ip rule del fwmark $routeMark table $routeTable prio 20 2>/dev/null || true",
            removeManagedRoutingTablesCommand(routeTables, "ip rule"),
            removeManagedRoutingTablesCommand(routeTables, "ip -6 rule"),
            flushRouteTablesCommand(routeTables, "ip route"),
            flushRouteTablesCommand(routeTables, "ip -6 route"),
            managedLinkRemovalCommand(interfaceNames),
        )
        return "status=0; " +
            commands.joinToString("; ") { command -> "( $command ) || status=1" } +
            "; exit \$status"
    }

    private fun defaultUidRoutingRuleCommands(
        routeTable: Int,
        bypassUids: Set<Int>,
        profileIds: Set<Int>,
        ruleCommand: String = "ip rule",
        priority: Int = DEFAULT_UID_RULE_PRIORITY,
    ): List<String> = routedUidRanges(bypassUids, profileIds).map { range ->
        uidRoutingRuleCommand(
            start = range.first,
            end = range.last,
            routeTable = routeTable,
            priority = priority,
            ruleCommand = ruleCommand,
        )
    }

    private fun routedUidRanges(
        bypassUids: Set<Int>,
        profileIds: Set<Int>,
    ): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        profileIds.toSortedSet().forEach { profileId ->
            val profileRange = appUidRangeForProfile(profileId)
            val excluded = bypassUids
                .filter { it in profileRange }
                .toSortedSet()
            var start = profileRange.first
            for (uid in excluded) {
                if (start < uid) {
                    ranges += start..(uid - 1)
                }
                start = uid + 1
            }
            if (start <= profileRange.last) {
                ranges += start..profileRange.last
            }
        }
        return ranges
    }

    private fun includedUidRoutingRuleCommands(
        routeTable: Int,
        uids: Set<Int>,
        priority: Int,
        ruleCommand: String = "ip rule",
    ): List<String> = includedUidRanges(uids).map { range ->
        uidRoutingRuleCommand(range.first, range.last, routeTable, priority, ruleCommand)
    }

    private fun includedUidRanges(uids: Set<Int>): List<IntRange> {
        val included = uids.filter(::isApplicationUid).toSortedSet()
        if (included.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var start = included.first()
        var previous = start
        included.drop(1).forEach { uid ->
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

    private fun uidRoutingRuleCommand(
        start: Int,
        end: Int,
        routeTable: Int,
        priority: Int = DEFAULT_UID_RULE_PRIORITY,
        ruleCommand: String = "ip rule",
    ): String = "$ruleCommand add iif lo uidrange $start-$end table $routeTable prio $priority"

    private fun tetherRoutingRuleCommand(routeTable: Int, ruleCommand: String = "ip rule"): String = "$ruleCommand add fwmark $TETHER_MARK_HEX/$TETHER_MARK_HEX table $routeTable prio $TETHER_RULE_PRIORITY"

    private fun tetherSetupCommand(
        tunName: String,
        upstreamInterface: String,
        allowIpv6: Boolean,
        bypassLan: Boolean,
    ): String {
        val tun = shellQuote(tunName)
        val upstream = shellQuote(upstreamInterface)
        val ipv4BypassCidrs = IPV4_ALWAYS_BYPASS_CIDRS + IPV4_LAN_CIDRS.takeIf { bypassLan }.orEmpty()
        val ipv6BypassCidrs = IPV6_ALWAYS_BYPASS_CIDRS + IPV6_LAN_CIDRS.takeIf { bypassLan }.orEmpty()
        return buildList {
            add("echo 0 > ${shellQuote("/proc/sys/net/ipv4/conf/$tunName/rp_filter")} 2>/dev/null || true")
            addAll(tetherMangleSetup("iptables -w", tun, upstream, ipv4BypassCidrs))
            addAll(tetherDnsSetup("iptables -w", TETHER_DNS_IPV4))
            addAll(
                tetherForwardSetup(
                    "iptables -w",
                    tun,
                    upstream,
                    allowTraffic = true,
                    bypassCidrs = ipv4BypassCidrs,
                ),
            )
            addAll(tetherMangleSetup("ip6tables -w", tun, upstream, ipv6BypassCidrs))
            if (allowIpv6) {
                addAll(tetherDnsSetup("ip6tables -w", TETHER_DNS_IPV6))
            }
            addAll(tetherForwardSetup("ip6tables -w", tun, upstream, allowIpv6, ipv6BypassCidrs))
        }.joinToString(" && ")
    }

    private fun tetherMangleSetup(
        tool: String,
        tunName: String,
        upstreamInterface: String,
        bypassCidrs: List<String>,
    ): List<String> = buildList {
        add("$tool -t mangle -N $TETHER_PREROUTING_CHAIN")
        add("$tool -t mangle -A $TETHER_PREROUTING_CHAIN -i $tunName -j RETURN")
        add("$tool -t mangle -A $TETHER_PREROUTING_CHAIN -i $upstreamInterface -j RETURN")
        for (protocol in listOf("tcp", "udp")) {
            add(
                "$tool -t mangle -A $TETHER_PREROUTING_CHAIN -p $protocol --dport 53 " +
                    "-j MARK --set-xmark $TETHER_MARK_HEX/$TETHER_MARK_HEX",
            )
        }
        add("$tool -t mangle -A $TETHER_PREROUTING_CHAIN -m addrtype --dst-type LOCAL -j RETURN")
        bypassCidrs.forEach { cidr ->
            add("$tool -t mangle -A $TETHER_PREROUTING_CHAIN -d $cidr -j RETURN")
        }
        add(
            "$tool -t mangle -A $TETHER_PREROUTING_CHAIN " +
                "-j MARK --set-xmark $TETHER_MARK_HEX/$TETHER_MARK_HEX",
        )
        add("$tool -t mangle -I PREROUTING 1 -j $TETHER_PREROUTING_CHAIN")
    }

    private fun tetherDnsSetup(tool: String, destination: String): List<String> = buildList {
        add("$tool -t nat -N $TETHER_DNS_CHAIN")
        for (protocol in listOf("tcp", "udp")) {
            add(
                "$tool -t nat -A $TETHER_DNS_CHAIN -m mark --mark $TETHER_MARK_HEX/$TETHER_MARK_HEX " +
                    "-m addrtype --dst-type LOCAL -p $protocol --dport 53 -j DNAT --to-destination $destination",
            )
        }
        add("$tool -t nat -I PREROUTING 1 -j $TETHER_DNS_CHAIN")
    }

    private fun tetherForwardSetup(
        tool: String,
        tunName: String,
        upstreamInterface: String,
        allowTraffic: Boolean,
        bypassCidrs: List<String>,
    ): List<String> = buildList {
        add("$tool -t filter -N $TETHER_FORWARD_CHAIN")
        add("$tool -t filter -A $TETHER_FORWARD_CHAIN -i $upstreamInterface -j RETURN")
        add("$tool -t filter -A $TETHER_FORWARD_CHAIN -i $tunName -j ACCEPT")
        if (allowTraffic) {
            add("$tool -t filter -A $TETHER_FORWARD_CHAIN -o $tunName -j ACCEPT")
            bypassCidrs.forEach { cidr ->
                add("$tool -t filter -A $TETHER_FORWARD_CHAIN -d $cidr -j RETURN")
            }
            val reject = if (tool.startsWith("ip6tables")) {
                "REJECT --reject-with icmp6-no-route"
            } else {
                "REJECT --reject-with icmp-net-unreachable"
            }
            add("$tool -t filter -A $TETHER_FORWARD_CHAIN -j $reject")
        } else {
            for (protocol in listOf("tcp", "udp")) {
                add(
                    "$tool -t filter -A $TETHER_FORWARD_CHAIN -p $protocol --dport 53 " +
                        "-j REJECT --reject-with icmp6-no-route",
                )
            }
            add("$tool -t filter -A $TETHER_FORWARD_CHAIN -m addrtype --dst-type LOCAL -j RETURN")
            bypassCidrs.forEach { cidr ->
                add("$tool -t filter -A $TETHER_FORWARD_CHAIN -d $cidr -j RETURN")
            }
            add("$tool -t filter -A $TETHER_FORWARD_CHAIN -j REJECT --reject-with icmp6-no-route")
        }
        add("$tool -t filter -I FORWARD 1 -j $TETHER_FORWARD_CHAIN")
        if (!allowTraffic) {
            add("$tool -t filter -I INPUT 1 -j $TETHER_FORWARD_CHAIN")
        }
    }

    private fun tetherCleanupCommand(): String = buildList {
        for (tool in listOf("iptables -w", "ip6tables -w")) {
            val saveTool = tool.substringBefore(' ') + "-save"
            val cleanup = listOf(
                "while $tool -t mangle -D PREROUTING -j $TETHER_PREROUTING_CHAIN 2>/dev/null; do :; done",
                "$tool -t mangle -F $TETHER_PREROUTING_CHAIN 2>/dev/null || true",
                "$tool -t mangle -X $TETHER_PREROUTING_CHAIN 2>/dev/null || true",
                "while $tool -t nat -D PREROUTING -j $TETHER_DNS_CHAIN 2>/dev/null; do :; done",
                "$tool -t nat -F $TETHER_DNS_CHAIN 2>/dev/null || true",
                "$tool -t nat -X $TETHER_DNS_CHAIN 2>/dev/null || true",
                "while $tool -t filter -D INPUT -j $TETHER_FORWARD_CHAIN 2>/dev/null; do :; done",
                "while $tool -t filter -D FORWARD -j $TETHER_FORWARD_CHAIN 2>/dev/null; do :; done",
                "$tool -t filter -F $TETHER_FORWARD_CHAIN 2>/dev/null || true",
                "$tool -t filter -X $TETHER_FORWARD_CHAIN 2>/dev/null || true",
                "! $tool -t mangle -C PREROUTING -j $TETHER_PREROUTING_CHAIN 2>/dev/null",
                "! $tool -t nat -C PREROUTING -j $TETHER_DNS_CHAIN 2>/dev/null",
                "! $tool -t filter -C INPUT -j $TETHER_FORWARD_CHAIN 2>/dev/null",
                "! $tool -t filter -C FORWARD -j $TETHER_FORWARD_CHAIN 2>/dev/null",
            ).joinToString("; ")
            add(
                "if rules=\$($saveTool 2>/dev/null); then " +
                    "case \"\$rules\" in *$TETHER_PREROUTING_CHAIN*|*$TETHER_DNS_CHAIN*|" +
                    "*$TETHER_FORWARD_CHAIN*) $cleanup;; esac; else $cleanup; fi",
            )
        }
    }.joinToString("; ")

    private fun physicalBypassRouteCommand(
        bypassTable: Int,
        physicalRoute: PhysicalRoute,
    ): String = if (physicalRoute.gateway != null) {
        "ip route replace default via ${physicalRoute.gateway} dev ${physicalRoute.dev} table $bypassTable"
    } else {
        "ip route replace default dev ${physicalRoute.dev} table $bypassTable"
    }

    private suspend fun executeRoutingCommands(
        commands: List<String>,
        force: Boolean = false,
        continueOnFailure: Boolean = false,
    ): RoutingResult {
        if (commands.isEmpty()) return RoutingResult(success = true)
        require(
            commands.all { command ->
                command.startsWith(IPV4_RULE_COMMAND_PREFIX) || command.startsWith(IPV6_RULE_COMMAND_PREFIX)
            },
        ) { "Only IPv4 and IPv6 rule commands can be batched" }
        val commandGroups = listOf(
            commands.filter { it.startsWith(IPV4_RULE_COMMAND_PREFIX) },
            commands.filter { it.startsWith(IPV6_RULE_COMMAND_PREFIX) },
        )
        val batches = commandGroups.flatMap { group ->
            group.chunked(IP_RULE_BATCH_SIZE).map { chunk -> ipRuleBatchCommand(chunk, force) }
        }
        val command = if (continueOnFailure) {
            "status=0; ${batches.joinToString("; ") { batch -> "( $batch ) || status=1" }}; exit \$status"
        } else {
            batches.joinToString(" && ")
        }
        val result = executeCommand(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError(command)
    }

    private fun flushRouteTablesCommand(routeTables: List<Int>, routeCommand: String): String = routeTables.distinct()
        .joinToString(" && ") { table -> "$routeCommand flush table $table 2>/dev/null" }

    private fun removeManagedRoutingTablesCommand(routeTables: List<Int>, ruleCommand: String): String {
        val tables = routeTables.distinct().joinToString(" ")
        if (tables.isBlank()) return "true"
        val ipCommand = if (ruleCommand == "ip -6 rule") "ip -6" else "ip"
        return "tables='$tables'; rules=\$($ruleCommand show 2>/dev/null) || exit 1; " +
            "batch=\$(printf '%s\\n' \"\$rules\" | while IFS= read -r line; do " +
            "pref=\${line%%:*}; case \"\$pref\" in ''|*[!0-9]*) continue;; esac; " +
            "for table in \$tables; do " +
            "case \" \$line \" in *\" lookup \$table \"*) " +
            "printf 'rule del %s\\n' \"\${line#*:}\"; break;; " +
            "esac; " +
            "done; " +
            "done); [ -z \"\$batch\" ] || printf '%s\\n' \"\$batch\" | " +
            "$ipCommand -force -batch - >/dev/null 2>&1"
    }

    private suspend fun installRoutingUpdateGuard(
        guardTable: Int,
        profileIds: Set<Int>,
        bypassUids: Set<Int>,
    ): RoutingResult {
        // Root mode requires both families so a policy update can never leak over IPv6.
        val ipv4 = listOf("route replace unreachable default table $guardTable") +
            updateGuardRuleCommands(guardTable, profileIds, bypassUids, "ip rule", operation = "add")
                .map { it.removePrefix("ip ") }
        val ipv6 = listOf("route replace unreachable default table $guardTable") +
            updateGuardRuleCommands(guardTable, profileIds, bypassUids, "ip -6 rule", operation = "add")
                .map { it.removePrefix("ip -6 ") }
        val command = ipBatchCommand(ipv4, ipv6 = false) + " && " + ipBatchCommand(ipv6, ipv6 = true)
        val result = executeCommand(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError("routing update guard setup")
    }

    private suspend fun removeRoutingUpdateGuard(
        guardTable: Int,
        profileIds: Set<Int>,
        bypassUids: Set<Int>,
    ): RoutingResult {
        val flushCommand = listOf(
            flushRouteTablesCommand(listOf(guardTable), "ip route"),
            flushRouteTablesCommand(listOf(guardTable), "ip -6 route"),
        ).joinToString(" && ")
        val ipv4Deletion = updateGuardRuleCommands(guardTable, profileIds, bypassUids, "ip rule", operation = "del")
            .map { it.removePrefix("ip ") }
        val ipv6Deletion = updateGuardRuleCommands(guardTable, profileIds, bypassUids, "ip -6 rule", operation = "del")
            .map { it.removePrefix("ip -6 ") }
        val command = buildString {
            append(ipBatchCommand(ipv4Deletion, ipv6 = false, force = true))
            append(" && ")
            append(ipBatchCommand(ipv6Deletion, ipv6 = true, force = true))
            append(" && v4_remaining=\$(ip rule show table $guardTable) ")
            append("&& v6_remaining=\$(ip -6 rule show table $guardTable) ")
            append("&& remaining=\"\$v4_remaining\n\$v6_remaining\" ")
            append("&& case \"\$remaining\" in *'$UPDATE_GUARD_PRIORITY:'*) false;; *) true;; esac && ")
            append(flushCommand)
        }
        val result = executeCommand(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError("routing update guard removal")
    }

    private fun updateGuardRuleCommands(
        guardTable: Int,
        profileIds: Set<Int>,
        bypassUids: Set<Int>,
        ruleCommand: String,
        operation: String,
    ): List<String> = routedUidRanges(bypassUids, profileIds).map { range ->
        "$ruleCommand $operation iif lo uidrange ${range.first}-${range.last} " +
            "table $guardTable prio $UPDATE_GUARD_PRIORITY"
    }

    private fun ipRuleBatchCommand(commands: List<String>, force: Boolean = false): String {
        if (commands.isEmpty()) return "true"
        val ipv6 = commands.first().startsWith(IPV6_RULE_COMMAND_PREFIX)
        val prefix = if (ipv6) IPV6_RULE_COMMAND_PREFIX else IPV4_RULE_COMMAND_PREFIX
        require(commands.all { it.startsWith(prefix) }) { "Mixed IP rule address families" }
        val commandPrefix = if (ipv6) "ip -6 " else "ip "
        val batchLines = commands.map { command -> command.removePrefix(commandPrefix) }
        val arguments = batchLines.joinToString(" ") { line -> shellQuote(line) }
        val ipCommand = if (ipv6) "ip -6" else "ip"
        val forceOption = if (force) " -force" else ""
        return "printf '%s\\n' $arguments | $ipCommand$forceOption -batch -"
    }

    private fun ipBatchCommand(commands: List<String>, ipv6: Boolean, force: Boolean = false): String {
        val arguments = commands.joinToString(" ") { line -> shellQuote(line) }
        val ipCommand = if (ipv6) "ip -6" else "ip"
        val forceOption = if (force) " -force" else ""
        return "printf '%s\\n' $arguments | $ipCommand$forceOption -batch -"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun parseDefaultRoute(line: String): PhysicalRoute? {
        val fields = line.trim().split(Regex("\\s+"))
        val devIndex = fields.indexOf("dev")
        if (devIndex == -1 || devIndex + 1 >= fields.size) return null

        val viaIndex = fields.indexOf("via")
        val tableIndex = fields.indexOf("table")
        return PhysicalRoute(
            dev = fields[devIndex + 1],
            gateway = if (viaIndex != -1 && viaIndex + 1 < fields.size) fields[viaIndex + 1] else null,
            table = if (tableIndex != -1 && tableIndex + 1 < fields.size) fields[tableIndex + 1] else null,
        )
    }

    private fun RootShell.Result.toRoutingError(command: String): RoutingResult {
        val details = listOf(output.trim(), error.trim()).filter { it.isNotEmpty() }.joinToString(" | ")
        return RoutingResult(
            success = false,
            error = if (details.isEmpty()) {
                "$command (exit=$exitCode)"
            } else {
                "$command (exit=$exitCode): $details"
            },
        )
    }

    private fun RootShell.Result.toCommandError(command: String): String {
        val details = listOf(output.trim(), error.trim()).filter { it.isNotEmpty() }.joinToString(" | ")
        return if (details.isEmpty()) {
            "$command (exit=$exitCode)"
        } else {
            "$command (exit=$exitCode): $details"
        }
    }

    private fun String.referencesAnyLookupTable(routeTables: Set<Int>): Boolean {
        val fields = trim().split(Regex("\\s+"))
        return fields.zipWithNext().any { (key, value) ->
            key == "lookup" && (value.toIntOrNull() ?: NAMED_ROUTE_TABLES[value]) in routeTables
        }
    }

    companion object {
        private const val APP_ROUTE_TABLE_OFFSET = 10
        private const val UPDATE_GUARD_ROUTE_TABLE_OFFSET = 2
        private const val MAX_APP_TUN_ROUTES = 64
        private const val APP_UID_RULE_PRIORITY = 12000
        private const val DEFAULT_UID_RULE_PRIORITY = 12010
        private const val UPDATE_GUARD_PRIORITY = 11999
        private const val TETHER_RULE_PRIORITY = 11998
        private const val TETHER_MARK_HEX = "0x10000000"
        private const val TETHER_PREROUTING_CHAIN = "MXTP"
        private const val TETHER_DNS_CHAIN = "MXTD"
        private const val TETHER_FORWARD_CHAIN = "MXTF"
        private const val TETHER_DNS_IPV4 = "198.18.0.1"
        private const val TETHER_DNS_IPV6 = "2001:db8::1"
        private const val IPV4_RULE_COMMAND_PREFIX = "ip rule "
        private const val IPV6_RULE_COMMAND_PREFIX = "ip -6 rule "
        private const val IP_RULE_BATCH_SIZE = 128
        private const val TUN_WAIT_TIMEOUT_EXIT_CODE = 124
        private const val TUN_PROCESS_EXIT_CODE = 125
        private val NAMED_ROUTE_TABLES = mapOf("default" to 253, "main" to 254, "local" to 255)
        private val IPV4_ALWAYS_BYPASS_CIDRS = listOf(
            "0.0.0.0/8",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "224.0.0.0/4",
            "240.0.0.0/4",
        )
        private val IPV4_LAN_CIDRS = listOf("10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16")
        private val IPV6_ALWAYS_BYPASS_CIDRS = listOf("::/128", "::1/128", "fe80::/10", "ff00::/8")
        private val IPV6_LAN_CIDRS = listOf("fc00::/7")
        const val DEFAULT_TUN_ADDRESS_CIDR = "10.0.0.1/30"
        const val DEFAULT_TUN_IPV6_ADDRESS_CIDR = "fd10:10:14::1/64"
        private const val TUN_WAIT_ATTEMPTS = 120
        private const val TUN_WAIT_CHUNK_ATTEMPTS = 5
        private const val TUN_WAIT_POLL_INTERVAL_MS = 50L

        fun appTunName(baseTunName: String, index: Int): String {
            val suffix = "a$index"
            val prefixLength = (15 - suffix.length).coerceAtLeast(1)
            return baseTunName.take(prefixLength) + suffix
        }

        fun appRouteTable(baseRouteTable: Int, index: Int): Int = baseRouteTable + APP_ROUTE_TABLE_OFFSET + index - 1

        fun appTunAddressCidr(index: Int): String = "10.0.${index.coerceIn(1, 254)}.1/30"

        fun appTunIpv6AddressCidr(index: Int): String = "fd10:10:14:${index.coerceIn(1, 254).toString(16)}::1/64"

        internal fun nextAvailableWlanName(interfaceNames: Sequence<String>): String {
            val occupiedNames = interfaceNames.toSet()
            return (0..occupiedNames.size)
                .asSequence()
                .map { index -> "wlan$index" }
                .first { candidate -> candidate !in occupiedNames }
        }

        internal fun parseLinkInterfaceName(line: String): String? = line
            .substringAfter(": ", missingDelimiterValue = "")
            .substringBefore(':')
            .substringBefore('@')
            .trim()
            .takeIf { it.isNotEmpty() }

        internal fun ipv6TunRouteCommand(tunName: String, routeTable: Int, allowIpv6: Boolean): String = if (allowIpv6) {
            "ip -6 route replace default dev $tunName table $routeTable"
        } else {
            "ip -6 route replace unreachable default table $routeTable"
        }

        internal fun isManagedTunName(interfaceName: String, baseTunName: String): Boolean = interfaceName == baseTunName ||
            (1..MAX_APP_TUN_ROUTES).any { index -> interfaceName == appTunName(baseTunName, index) }

        private fun appRouteTables(baseRouteTable: Int, count: Int): List<Int> = (1..count.coerceIn(0, MAX_APP_TUN_ROUTES)).map { appRouteTable(baseRouteTable, it) }
    }
}

internal fun managedLinkRemovalCommand(interfaceNames: List<String>): String {
    val interfaces = interfaceNames.joinToString(" ") { value -> "'${value.replace("'", "'\\''")}'" }
    return "for interface in $interfaces; do " +
        "if ip link show \"\$interface\" >/dev/null 2>&1; then " +
        "ip link del \"\$interface\" || " +
        "{ if ip link show \"\$interface\" >/dev/null 2>&1; then exit 1; fi; }; fi; done"
}
