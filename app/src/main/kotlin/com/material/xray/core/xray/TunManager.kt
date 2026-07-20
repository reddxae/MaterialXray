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
        val ipv6GuardTable = routeTable + IPV6_GUARD_ROUTE_TABLE_OFFSET

        val bypassRoute = if (physicalRoute.gateway != null) {
            "ip route replace default via ${physicalRoute.gateway} dev ${physicalRoute.dev} table $bypassTable"
        } else {
            "ip route replace default dev ${physicalRoute.dev} table $bypassTable"
        }
        val bypassRule = "ip rule add fwmark $fwmark table $bypassTable prio 10"
        val tunRoute = "ip route replace default dev $tunName table $routeTable"
        val routeTables = listOf(bypassTable, routeTable) + managedAppTables
        val setupCommands = buildList {
            add(installIpv6UpdateGuardCommand(ipv6GuardTable, bypassUids, routedProfileIds))
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

        val guardRemovalResult = executeCommand(removeIpv6UpdateGuardCommand(ipv6GuardTable, verify = true))
        return if (guardRemovalResult.isSuccess) {
            RoutingResult(success = true)
        } else {
            guardRemovalResult.toRoutingError("IPv6 routing update guard removal")
        }
    }

    suspend fun removeRouting(
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        tunName: String,
        managedAppRouteCount: Int = MAX_APP_TUN_ROUTES,
    ) {
        val bypassTable = routeTable + 1
        val ipv6GuardTable = routeTable + IPV6_GUARD_ROUTE_TABLE_OFFSET
        executeCommand(
            listOf(
                "ip rule del fwmark $fwmark table main prio 10 2>/dev/null",
                "ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null",
                "ip rule del fwmark $routeMark table $routeTable prio 20 2>/dev/null",
            ).joinToString("; "),
        )
        val appTables = appRouteTables(routeTable, managedAppRouteCount)
        removeManagedRoutingTables(routeTable, listOf(bypassTable, ipv6GuardTable) + appTables)
        flushRouteTables(listOf(bypassTable, routeTable, ipv6GuardTable) + appTables)
        val linkDeleteCommands = buildList {
            add("ip link del $tunName 2>/dev/null")
            for (index in 1..managedAppRouteCount.coerceIn(0, MAX_APP_TUN_ROUTES)) {
                add("ip link del ${appTunName(tunName, index)} 2>/dev/null")
            }
        }
        executeCommand(linkDeleteCommands.joinToString("; "))
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
    ): List<String> {
        val included = uids.filter(::isApplicationUid).toSortedSet()
        if (included.isEmpty()) return emptyList()

        return contiguousUidRoutingRuleCommands(
            routeTable = routeTable,
            uids = included,
            priority = priority,
            ruleCommand = ruleCommand,
        )
    }

    private fun contiguousUidRoutingRuleCommands(
        routeTable: Int,
        uids: Set<Int>,
        priority: Int,
        ruleCommand: String,
    ): List<String> {
        val commands = mutableListOf<String>()
        var start = uids.first()
        var previous = start
        uids.drop(1).forEach { uid ->
            if (uid == previous + 1) {
                previous = uid
            } else {
                commands += uidRoutingRuleCommand(start, previous, routeTable, priority, ruleCommand)
                start = uid
                previous = uid
            }
        }
        commands += uidRoutingRuleCommand(start, previous, routeTable, priority, ruleCommand)
        return commands
    }

    private fun uidRoutingRuleCommand(
        start: Int,
        end: Int,
        routeTable: Int,
        priority: Int = DEFAULT_UID_RULE_PRIORITY,
        ruleCommand: String = "ip rule",
    ): String = "$ruleCommand add iif lo uidrange $start-$end table $routeTable prio $priority"

    private suspend fun executeRoutingCommands(commands: List<String>): RoutingResult {
        if (commands.isEmpty()) return RoutingResult(success = true)
        val command = commands.joinToString(" && ")
        val result = executeCommand(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError(command)
    }

    private suspend fun flushRouteTables(routeTables: List<Int>) {
        if (routeTables.isEmpty()) return
        executeCommand(
            listOf(
                flushRouteTablesCommand(routeTables, "ip route"),
                flushRouteTablesCommand(routeTables, "ip -6 route"),
            ).joinToString("; "),
        )
    }

    private suspend fun removeManagedRoutingTables(routeTable: Int, appRouteTables: List<Int>) {
        val managedTables = (listOf(routeTable) + appRouteTables).toSet()
        listOf("ip rule", "ip -6 rule").forEach { ruleCommand ->
            val result = executeCommand("$ruleCommand show")
            val prefs = result.output
                .lineSequence()
                .filter { line -> line.referencesAnyLookupTable(managedTables) }
                .mapNotNull { line -> line.substringBefore(':').trim().takeIf { it.isNotEmpty() } }
                .distinct()
                .toList()
            if (prefs.isEmpty()) return@forEach

            val command = prefs.joinToString("; ") { pref ->
                "while $ruleCommand del pref $pref 2>/dev/null; do :; done"
            }
            executeCommand(command)
        }
    }

    private fun flushRouteTablesCommand(routeTables: List<Int>, routeCommand: String): String = routeTables.distinct().joinToString("; ") { table ->
        "$routeCommand flush table $table 2>/dev/null || true"
    }

    private fun removeManagedRoutingTablesCommand(routeTables: List<Int>, ruleCommand: String): String {
        val tables = routeTables.distinct().joinToString(" ")
        if (tables.isBlank()) return "true"
        return "tables='$tables'; " +
            "$ruleCommand show 2>/dev/null | while IFS= read -r line; do " +
            "pref=\${line%%:*}; " +
            "case \"\$pref\" in ''|*[!0-9]*) continue;; esac; " +
            "for table in \$tables; do " +
            "case \" \$line \" in *\" lookup \$table \"*) " +
            "while $ruleCommand del pref \"\$pref\" 2>/dev/null; do :; done; break;; " +
            "esac; " +
            "done; " +
            "done || true"
    }

    private fun installIpv6UpdateGuardCommand(
        guardTable: Int,
        bypassUids: Set<Int>,
        profileIds: Set<Int>,
    ): String = buildList {
        add("ip -6 route replace unreachable default table $guardTable")
        routedUidRanges(bypassUids, profileIds).forEach { range ->
            val uidRange = "${range.first}-${range.last}"
            add(
                "ip -6 rule show | grep -q '^$IPV6_UPDATE_GUARD_PRIORITY:.*iif lo.*uidrange $uidRange.*lookup $guardTable' || " +
                    "ip -6 rule add iif lo uidrange $uidRange table $guardTable prio $IPV6_UPDATE_GUARD_PRIORITY",
            )
        }
    }.joinToString(" && ")

    private fun removeIpv6UpdateGuardCommand(guardTable: Int, verify: Boolean = false): String = buildString {
        append("while ip -6 rule del table $guardTable pref $IPV6_UPDATE_GUARD_PRIORITY 2>/dev/null; do :; done")
        append("; ")
        append(flushRouteTablesCommand(listOf(guardTable), "ip -6 route"))
        if (verify) {
            append("; if ip -6 rule show | grep -Eq ' lookup $guardTable( |$)'; then false; else true; fi")
        }
    }

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
        private const val IPV6_GUARD_ROUTE_TABLE_OFFSET = 2
        private const val MAX_APP_TUN_ROUTES = 64
        private const val APP_UID_RULE_PRIORITY = 12000
        private const val DEFAULT_UID_RULE_PRIORITY = 12010
        private const val IPV6_UPDATE_GUARD_PRIORITY = 11999
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
