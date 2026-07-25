package com.material.xray.core.xray

import com.material.xray.core.app.appUidRangeForProfile
import com.material.xray.core.app.isApplicationUid
import com.material.xray.core.app.profileIdForUid
import com.material.xray.core.root.RootShell
import kotlinx.coroutines.delay

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
        isProcessAlive: suspend () -> Boolean = { true },
    ): TunSetupResult {
        var attempts = 0
        while (attempts < TUN_WAIT_ATTEMPTS) {
            val result = executeCommand("ip link show $tunName 2>/dev/null")
            if (result.isSuccess && result.output.contains(tunName)) break
            if (!isProcessAlive()) {
                return TunSetupResult(success = false, processExited = true)
            }
            delay(TUN_WAIT_POLL_INTERVAL_MS)
            attempts++
        }
        if (attempts >= TUN_WAIT_ATTEMPTS) {
            return if (isProcessAlive()) {
                TunSetupResult(success = false, error = "TUN interface $tunName did not come up within timeout")
            } else {
                TunSetupResult(success = false, processExited = true)
            }
        }

        val upCommand = "ip addr add $addressCidr dev $tunName 2>/dev/null; ip link set $tunName up"
        val upResult = executeCommand(upCommand)
        return if (upResult.isSuccess) {
            TunSetupResult(success = true)
        } else {
            TunSetupResult(success = false, error = upResult.toCommandError(upCommand))
        }
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

        val bypassRoute = physicalBypassRouteCommand(bypassTable, physicalRoute)
        val bypassRule = "ip rule add fwmark $fwmark table $bypassTable prio 10"
        val tunRoute = "ip route replace default dev $tunName table $routeTable"
        val routeTables = listOf(bypassTable, routeTable) + managedAppTables
        val setupCommands = buildList {
            add("ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null || true")
            add(removeManagedRoutingTablesCommand(routeTables, "ip rule"))
            add(removeManagedRoutingTablesCommand(routeTables, "ip -6 rule"))
            add(flushRouteTablesCommand(routeTables, "ip route"))
            add(flushRouteTablesCommand(routeTables, "ip -6 route"))
            add(bypassRoute)
            add(bypassRule)
            add(tunRoute)
            appTunRoutes.forEach { route ->
                add("ip route replace default dev ${route.tunName} table ${route.routeTable}")
            }
            add(ipv6TunRouteCommand(tunName, routeTable, allowIpv6))
            appTunRoutes.forEach { route ->
                add(ipv6TunRouteCommand(route.tunName, route.routeTable, allowIpv6))
            }
        }
        val setupCommand = setupCommands.joinToString(" && ")
        val setupResult = executeCommand(setupCommand)
        if (!setupResult.isSuccess) return setupResult.toRoutingError("initial IP routing setup")

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

        val routingResult = executeRoutingCommands(uidRoutingCommands + ipv6UidRoutingCommands)
        if (!routingResult.success) return routingResult

        val guardRemovalResult = executeCommand(
            removeRoutingUpdateGuardCommand(updateGuardTable, verify = true),
        )
        return if (guardRemovalResult.isSuccess) {
            RoutingResult(success = true)
        } else {
            guardRemovalResult.toRoutingError("routing update guard removal")
        }
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
    ): Boolean {
        val bypassTable = routeTable + 1
        val updateGuardTable = routeTable + UPDATE_GUARD_ROUTE_TABLE_OFFSET
        var success = executeCommand(
            listOf(
                "ip rule del fwmark $fwmark table main prio 10 2>/dev/null || true",
                "ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null || true",
                "ip rule del fwmark $routeMark table $routeTable prio 20 2>/dev/null || true",
            ).joinToString("; "),
        ).isSuccess
        val appTables = appRouteTables(routeTable, managedAppRouteCount)
        success = removeManagedRoutingTables(routeTable, listOf(bypassTable, updateGuardTable) + appTables) && success
        success = flushRouteTables(listOf(bypassTable, routeTable, updateGuardTable) + appTables) && success
        val interfaceNames = buildList {
            add(tunName)
            for (index in 1..managedAppRouteCount.coerceIn(0, MAX_APP_TUN_ROUTES)) {
                add(appTunName(tunName, index))
            }
        }
        val linkResult = executeCommand(managedLinkRemovalCommand(interfaceNames))
        return linkResult.isSuccess && success
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

    private fun physicalBypassRouteCommand(
        bypassTable: Int,
        physicalRoute: PhysicalRoute,
    ): String = if (physicalRoute.gateway != null) {
        "ip route replace default via ${physicalRoute.gateway} dev ${physicalRoute.dev} table $bypassTable"
    } else {
        "ip route replace default dev ${physicalRoute.dev} table $bypassTable"
    }

    private suspend fun executeRoutingCommands(commands: List<String>): RoutingResult {
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
        for (group in commandGroups) {
            if (group.isEmpty()) continue
            for (chunk in group.chunked(IP_RULE_BATCH_SIZE)) {
                val command = ipRuleBatchCommand(chunk)
                val result = executeCommand(command)
                if (!result.isSuccess) return result.toRoutingError(command)
            }
        }
        return RoutingResult(success = true)
    }

    private suspend fun flushRouteTables(routeTables: List<Int>): Boolean {
        if (routeTables.isEmpty()) return true
        return executeCommand(
            listOf(
                flushRouteTablesCommand(routeTables, "ip route"),
                optionalIpv6FlushRouteTablesCommand(routeTables),
            ).joinToString(" && "),
        ).isSuccess
    }

    private suspend fun removeManagedRoutingTables(routeTable: Int, appRouteTables: List<Int>): Boolean {
        val managedTables = (listOf(routeTable) + appRouteTables).toSet()
        var success = true
        listOf("ip rule", "ip -6 rule").forEach { ruleCommand ->
            val result = executeCommand("$ruleCommand show")
            if (!result.isSuccess) {
                success = false
                return@forEach
            }
            val commands = result.output
                .lineSequence()
                .filter { line -> line.referencesAnyLookupTable(managedTables) }
                .mapNotNull { line ->
                    line.substringAfter(':', missingDelimiterValue = "")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { rule -> "$ruleCommand del $rule" }
                }
                .toList()
            if (commands.isEmpty()) return@forEach

            commands.chunked(IP_RULE_BATCH_SIZE).forEach { chunk ->
                if (!executeCommand(ipRuleBatchCommand(chunk, force = true)).isSuccess) success = false
            }
        }
        return success
    }

    private fun flushRouteTablesCommand(routeTables: List<Int>, routeCommand: String): String = routeTables.distinct().joinToString(" && ") { table ->
        "if $routeCommand show table $table >/dev/null 2>&1; then " +
            "$routeCommand flush table $table 2>/dev/null; fi"
    }

    private fun optionalIpv6FlushRouteTablesCommand(routeTables: List<Int>): String = flushRouteTablesCommand(routeTables, "ip -6 route")

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
        val routeCommand = "ip route replace unreachable default table $guardTable && " +
            "ip -6 route replace unreachable default table $guardTable"
        val routeResult = executeCommand(routeCommand)
        if (!routeResult.isSuccess) return routeResult.toRoutingError(routeCommand)

        return executeRoutingCommands(
            updateGuardRuleCommands(guardTable, profileIds, bypassUids, "ip rule", operation = "add") +
                updateGuardRuleCommands(guardTable, profileIds, bypassUids, "ip -6 rule", operation = "add"),
        )
    }

    private fun removeRoutingUpdateGuardCommand(
        guardTable: Int,
        verify: Boolean = false,
    ): String = listOf(
        removeUpdateGuardCommand(guardTable, "ip rule", "ip route", verify),
        removeUpdateGuardCommand(guardTable, "ip -6 rule", "ip -6 route", verify),
    ).joinToString(" && ")

    private fun removeUpdateGuardCommand(
        guardTable: Int,
        ruleCommand: String,
        routeCommand: String,
        verify: Boolean,
    ): String = buildString {
        val guardPattern = "^$UPDATE_GUARD_PRIORITY:"
        append("while $ruleCommand show table $guardTable 2>/dev/null | grep -q ${shellQuote(guardPattern)}; do ")
        append("$ruleCommand del pref $UPDATE_GUARD_PRIORITY table $guardTable || exit 1; done")
        if (verify) {
            append("; guard_rules=\$($ruleCommand show table $guardTable 2>/dev/null) || exit 1")
            append("; printf '%s\\n' \"\$guard_rules\" | grep -Eq ${shellQuote(guardPattern)}")
            append("; guard_status=\$?")
            append("; if [ \$guard_status -eq 0 ]; then false")
            append("; elif [ \$guard_status -ne 1 ]; then exit \$guard_status")
            append("; else if $routeCommand show table $guardTable >/dev/null 2>&1; then ")
            append("$routeCommand flush table $guardTable; fi; fi")
        } else {
            append("; if $routeCommand show table $guardTable >/dev/null 2>&1; then ")
            append("$routeCommand flush table $guardTable; fi")
        }
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
            key == "lookup" && value.toIntOrNull() in routeTables
        }
    }

    companion object {
        private const val APP_ROUTE_TABLE_OFFSET = 10
        private const val UPDATE_GUARD_ROUTE_TABLE_OFFSET = 2
        private const val MAX_APP_TUN_ROUTES = 64
        private const val APP_UID_RULE_PRIORITY = 12000
        private const val DEFAULT_UID_RULE_PRIORITY = 12010
        private const val UPDATE_GUARD_PRIORITY = 11999
        private const val IPV4_RULE_COMMAND_PREFIX = "ip rule "
        private const val IPV6_RULE_COMMAND_PREFIX = "ip -6 rule "
        private const val IP_RULE_BATCH_SIZE = 128
        const val DEFAULT_TUN_ADDRESS_CIDR = "10.0.0.1/30"
        private const val TUN_WAIT_ATTEMPTS = 120
        private const val TUN_WAIT_POLL_INTERVAL_MS = 50L

        fun appTunName(baseTunName: String, index: Int): String {
            val suffix = "a$index"
            val prefixLength = (15 - suffix.length).coerceAtLeast(1)
            return baseTunName.take(prefixLength) + suffix
        }

        fun appRouteTable(baseRouteTable: Int, index: Int): Int = baseRouteTable + APP_ROUTE_TABLE_OFFSET + index - 1

        fun appTunAddressCidr(index: Int): String = "10.0.${index.coerceIn(1, 254)}.1/30"

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
