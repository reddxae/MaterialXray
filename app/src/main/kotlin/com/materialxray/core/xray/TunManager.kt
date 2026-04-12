package com.materialxray.core.xray

import com.materialxray.core.root.RootShell
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

    suspend fun configureTun(
        tunName: String,
        isProcessAlive: suspend () -> Boolean = { true },
    ): TunSetupResult {
        var attempts = 0
        while (attempts < 30) {
            val result = shell.execute("ip link show $tunName 2>/dev/null")
            if (result.isSuccess && result.output.contains(tunName)) break
            if (!isProcessAlive()) {
                return TunSetupResult(success = false, processExited = true)
            }
            delay(200)
            attempts++
        }
        if (attempts >= 30) {
            return if (isProcessAlive()) {
                TunSetupResult(success = false, error = "TUN interface $tunName did not come up within timeout")
            } else {
                TunSetupResult(success = false, processExited = true)
            }
        }

        shell.execute("ip addr add 10.0.0.1/30 dev $tunName 2>/dev/null")
        val upResult = shell.execute("ip link set $tunName up")
        return if (upResult.isSuccess) {
            TunSetupResult(success = true)
        } else {
            TunSetupResult(success = false, error = upResult.toCommandError("ip link set $tunName up"))
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
    ): RoutingResult {
        shell.execute("ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null")
        removeRulesForTable(routeTable)
        shell.execute("ip route flush table $bypassTable 2>/dev/null")
        shell.execute("ip route flush table $routeTable 2>/dev/null")

        val bypassRoute = if (physicalRoute.gateway != null) {
            "ip route replace default via ${physicalRoute.gateway} dev ${physicalRoute.dev} table $bypassTable"
        } else {
            "ip route replace default dev ${physicalRoute.dev} table $bypassTable"
        }
        val bypassRouteResult = shell.execute(bypassRoute)
        if (!bypassRouteResult.isSuccess) return bypassRouteResult.toRoutingError(bypassRoute)

        val bypassRule = "ip rule add fwmark $fwmark table $bypassTable prio 10"
        val bypassRuleResult = shell.execute(bypassRule)
        if (!bypassRuleResult.isSuccess) return bypassRuleResult.toRoutingError(bypassRule)

        val tunRoute = "ip route replace default dev $tunName table $routeTable"
        val tunRouteResult = shell.execute(tunRoute)
        if (!tunRouteResult.isSuccess) return tunRouteResult.toRoutingError(tunRoute)

        return addUidRoutingRules(routeTable, bypassUids)
    }

    suspend fun removeRouting(fwmark: Int, routeMark: Int, routeTable: Int, tunName: String) {
        val bypassTable = routeTable + 1
        shell.execute("ip rule del fwmark $fwmark table main prio 10 2>/dev/null")
        shell.execute("ip rule del fwmark $fwmark table $bypassTable prio 10 2>/dev/null")
        shell.execute("ip rule del fwmark $routeMark table $routeTable prio 20 2>/dev/null")
        removeRulesForTable(routeTable)
        shell.execute("ip route flush table $bypassTable 2>/dev/null")
        shell.execute("ip route flush table $routeTable 2>/dev/null")
        shell.execute("ip link del $tunName 2>/dev/null")
    }

    private suspend fun addUidRoutingRules(routeTable: Int, bypassUids: Set<Int>): RoutingResult {
        val excluded = bypassUids.filter { it in APP_UID_MIN..APP_UID_MAX }.toSortedSet()
        var start = APP_UID_MIN
        for (uid in excluded) {
            if (start < uid) {
                addUidRoutingRule(start, uid - 1, routeTable).also {
                    if (!it.success) return it
                }
            }
            start = uid + 1
        }
        return if (start <= APP_UID_MAX) {
            addUidRoutingRule(start, APP_UID_MAX, routeTable)
        } else {
            RoutingResult(success = true)
        }
    }

    private suspend fun addUidRoutingRule(start: Int, end: Int, routeTable: Int): RoutingResult {
        val command = "ip rule add iif lo uidrange $start-$end table $routeTable prio $UID_RULE_PRIORITY"
        val result = shell.execute(command)
        return if (result.isSuccess) RoutingResult(success = true) else result.toRoutingError(command)
    }

    private suspend fun removeRulesForTable(routeTable: Int) {
        val result = shell.execute("ip rule show")
        result.output
            .lineSequence()
            .filter { line -> line.referencesLookupTable(routeTable) }
            .mapNotNull { line -> line.substringBefore(':').trim().takeIf { it.isNotEmpty() } }
            .forEach { pref ->
                while (shell.execute("ip rule del pref $pref 2>/dev/null").isSuccess) {
                    // Multiple rules can share the same preference.
                }
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

    private fun String.referencesLookupTable(routeTable: Int): Boolean {
        val fields = trim().split(Regex("\\s+"))
        return fields.zipWithNext().any { (key, value) ->
            key == "lookup" && value == routeTable.toString()
        }
    }

    private companion object {
        const val APP_UID_MIN = 10000
        const val APP_UID_MAX = 99999
        const val UID_RULE_PRIORITY = 12010
    }
}
