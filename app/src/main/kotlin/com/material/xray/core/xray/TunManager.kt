package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import com.material.xray.core.app.appUidRangeForProfile
import com.material.xray.core.app.isApplicationUid
import com.material.xray.core.app.profileIdForUid
import kotlinx.coroutines.delay

class TunManager(private val shell: RootShell) {
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

    suspend fun configureTun(
        tunName: String,
        addressCidr: String = DEFAULT_TUN_ADDRESS_CIDR,
        isProcessAlive: suspend () -> Boolean = { true },
    ): TunSetupResult {
        var attempts = 0
        while (attempts < TUN_WAIT_ATTEMPTS) {
            val result = shell.execute("ip link show $tunName 2>/dev/null")
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
        val upResult = shell.execute(upCommand)
        return if (upResult.isSuccess) {
            TunSetupResult(success = true)
        } else {
            TunSetupResult(success = false, error = upResult.toCommandError(upCommand))
        }
    }

    suspend fun detectPhysicalRoute(tunName: String): PhysicalRoute? {
        detectPhysicalRouteFromRouteGet(tunName)?.let { return it }

        val result = shell.execute("ip route show table all 2>/dev/null | grep '^default '")
        return result.output
            .lineSequence()
            .mapNotNull { parseDefaultRoute(it) }
            .sortedWith(compareByDescending<PhysicalRoute> { it.gateway != null }.thenBy { it.dev })
            .firstOrNull { route ->
                route.dev != tunName &&
                    !route.dev.startsWith("tun") &&
                    !route.dev.startsWith("xray") &&
                    route.dev != "dummy0"
            }
    }

    private suspend fun detectPhysicalRouteFromRouteGet(tunName: String): PhysicalRoute? {
        val result = shell.execute("ip route get 1.1.1.1 2>/dev/null")
        if (!result.isSuccess) return null

        return result.output
            .lineSequence()
            .mapNotNull { parseDefaultRoute(it) }
            .firstOrNull { route ->
                route.dev != tunName &&
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
        bypassUids: Set<Int>,
        appTunRoutes: List<AppTunRoute> = emptyList(),
        managedAppRouteCount: Int = appTunRoutes.size,
        routeProfileIds: Set<Int> = setOf(0),
    ): RoutingResult {
        val managedAppTables = appRouteTables(routeTable, managedAppRouteCount)
            .plus(appTunRoutes.map { it.routeTable })
            .distinct()

        val bypassRoute = if (physicalRoute.gateway != null) {
            "ip route replace default via ${physicalRoute.gateway} dev ${physicalRoute.dev} table $bypassTable"
        } else {
            "ip route replace default dev ${physicalRoute.dev} table $bypassTable"
        }
        val bypassRule = "ip rule add fwmark $fwmark table $bypassTable prio 10"
        val tunRoute = "ip route replace default dev $tunName table $routeTable"
        val routeTables = listOf(bypassTable, routeTable) + managedAppTables
        val setupCommands = buildList {
            add("ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null || true")
            add(removeManagedRoutingTablesCommand(routeTables))
            add(flushRouteTablesCommand(routeTables))
            add(bypassRoute)
            add(bypassRule)
            add(tunRoute)
            appTunRoutes.forEach { route ->
                add("ip route replace default dev ${route.tunName} table ${route.routeTable}")
            }
        }
        val setupCommand = setupCommands.joinToString(" && ")
        val setupResult = shell.execute(setupCommand)
        if (!setupResult.isSuccess) return setupResult.toRoutingError("initial IP routing setup")

        val appUids = appTunRoutes.flatMap { it.uids }.toSet()
        val routedProfileIds = (routeProfileIds + (bypassUids + appUids).map(::profileIdForUid))
            .filter { it >= 0 }
            .toSet()
            .ifEmpty { setOf(0) }
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

        return executeRoutingCommands(uidRoutingCommands)
    }

    suspend fun removeRouting(
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        tunName: String,
        managedAppRouteCount: Int = MAX_APP_TUN_ROUTES,
    ) {
        val bypassTable = routeTable + 1
        shell.execute(
            listOf(
                "ip rule del fwmark $fwmark table main prio 10 2>/dev/null",
                "ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null",
                "ip rule del fwmark $routeMark table $routeTable prio 20 2>/dev/null",
            ).joinToString("; ")
        )
        val appTables = appRouteTables(routeTable, managedAppRouteCount)
        removeManagedRoutingTables(routeTable, listOf(bypassTable) + appTables)
        flushRouteTables(listOf(bypassTable, routeTable) + appTables)
        val linkDeleteCommands = buildList {
            add("ip link del $tunName 2>/dev/null")
            for (index in 1..managedAppRouteCount.coerceIn(0, MAX_APP_TUN_ROUTES)) {
                add("ip link del ${appTunName(tunName, index)} 2>/dev/null")
            }
        }
        shell.execute(linkDeleteCommands.joinToString("; "))
    }

    private fun defaultUidRoutingRuleCommands(
        routeTable: Int,
        bypassUids: Set<Int>,
        profileIds: Set<Int>,
    ): List<String> {
        val commands = mutableListOf<String>()
        profileIds.toSortedSet().forEach { profileId ->
            val profileRange = appUidRangeForProfile(profileId)
            val excluded = bypassUids
                .filter { it in profileRange }
                .toSortedSet()
            var start = profileRange.first
            for (uid in excluded) {
                if (start < uid) {
                    commands += uidRoutingRuleCommand(start, uid - 1, routeTable)
                }
                start = uid + 1
            }
            if (start <= profileRange.last) {
                commands += uidRoutingRuleCommand(start, profileRange.last, routeTable)
            }
        }
        return commands
    }

    private fun includedUidRoutingRuleCommands(
        routeTable: Int,
        uids: Set<Int>,
        priority: Int,
    ): List<String> {
        val included = uids.filter(::isApplicationUid).toSortedSet()
        if (included.isEmpty()) return emptyList()

        return contiguousUidRoutingRuleCommands(
            routeTable = routeTable,
            uids = included,
            priority = priority,
        )
    }

    private fun contiguousUidRoutingRuleCommands(
        routeTable: Int,
        uids: Set<Int>,
        priority: Int,
    ): List<String> {
        val commands = mutableListOf<String>()
        var start = uids.first()
        var previous = start
        uids.drop(1).forEach { uid ->
            if (uid == previous + 1) {
                previous = uid
            } else {
                commands += uidRoutingRuleCommand(start, previous, routeTable, priority)
                start = uid
                previous = uid
            }
        }
        commands += uidRoutingRuleCommand(start, previous, routeTable, priority)
        return commands
    }

    private fun uidRoutingRuleCommand(
        start: Int,
        end: Int,
        routeTable: Int,
        priority: Int = DEFAULT_UID_RULE_PRIORITY,
    ): String = "ip rule add iif lo uidrange $start-$end table $routeTable prio $priority"

    private suspend fun executeRoutingCommands(commands: List<String>): RoutingResult {
        if (commands.isEmpty()) return RoutingResult(success = true)
        val command = commands.joinToString(" && ")
        val result = shell.execute(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError(command)
    }

    private suspend fun flushRouteTables(routeTables: List<Int>) {
        if (routeTables.isEmpty()) return
        shell.execute(flushRouteTablesCommand(routeTables))
    }

    private suspend fun removeManagedRoutingTables(routeTable: Int, appRouteTables: List<Int>) {
        val managedTables = (listOf(routeTable) + appRouteTables).toSet()
        val result = shell.execute("ip rule show")
        val prefs = result.output
            .lineSequence()
            .filter { line -> line.referencesAnyLookupTable(managedTables) }
            .mapNotNull { line -> line.substringBefore(':').trim().takeIf { it.isNotEmpty() } }
            .distinct()
            .toList()
        if (prefs.isEmpty()) return

        val command = prefs.joinToString("; ") { pref ->
            "while ip rule del pref $pref 2>/dev/null; do :; done"
        }
        shell.execute(command)
    }

    private fun flushRouteTablesCommand(routeTables: List<Int>): String =
        routeTables.distinct().joinToString("; ") { table ->
            "ip route flush table $table 2>/dev/null || true"
        }

    private fun removeManagedRoutingTablesCommand(routeTables: List<Int>): String {
        val tables = routeTables.distinct().joinToString(" ")
        if (tables.isBlank()) return "true"
        return "tables='$tables'; " +
            "ip rule show 2>/dev/null | while IFS= read -r line; do " +
            "pref=\${line%%:*}; " +
            "case \"\$pref\" in ''|*[!0-9]*) continue;; esac; " +
            "for table in \$tables; do " +
            "case \" \$line \" in *\" lookup \$table \"*) " +
            "while ip rule del pref \"\$pref\" 2>/dev/null; do :; done; break;; " +
            "esac; " +
            "done; " +
            "done || true"
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
        private const val MAX_APP_TUN_ROUTES = 64
        private const val APP_UID_RULE_PRIORITY = 12000
        private const val DEFAULT_UID_RULE_PRIORITY = 12010
        private const val DEFAULT_TUN_ADDRESS_CIDR = "10.0.0.1/30"
        private const val TUN_WAIT_ATTEMPTS = 120
        private const val TUN_WAIT_POLL_INTERVAL_MS = 50L

        fun appTunName(baseTunName: String, index: Int): String {
            val suffix = "a$index"
            val prefixLength = (15 - suffix.length).coerceAtLeast(1)
            return baseTunName.take(prefixLength) + suffix
        }

        fun appRouteTable(baseRouteTable: Int, index: Int): Int =
            baseRouteTable + APP_ROUTE_TABLE_OFFSET + index - 1

        fun appTunAddressCidr(index: Int): String =
            "10.0.${index.coerceIn(1, 254)}.1/30"

        private fun appRouteTables(baseRouteTable: Int, count: Int): List<Int> =
            (1..count.coerceIn(0, MAX_APP_TUN_ROUTES)).map { appRouteTable(baseRouteTable, it) }
    }
}
