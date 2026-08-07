package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunManagerTest {
    @Test
    fun `automatic name uses wlan0 when no wlan names exist`() {
        val name = TunManager.nextAvailableWlanName(sequenceOf("lo", "rmnet0"))

        assertEquals("wlan0", name)
    }

    @Test
    fun `automatic name uses the lowest gap in occupied wlan names`() {
        val name = TunManager.nextAvailableWlanName(
            sequenceOf("lo", "wlan0", "wlan1", "wlan3", "rmnet0"),
        )

        assertEquals("wlan2", name)
    }

    @Test
    fun `link parser handles peer suffix from one-line ip output`() {
        val name = TunManager.parseLinkInterfaceName(
            "37: wlan1@if5: <POINTOPOINT,UP> mtu 1500 qdisc pfifo_fast state UNKNOWN",
        )

        assertEquals("wlan1", name)
    }

    @Test
    fun `link parser rejects malformed output`() {
        assertNull(TunManager.parseLinkInterfaceName("not an ip link line"))
    }

    @Test
    fun `managed TUN names include app routing interfaces`() {
        assertTrue(TunManager.isManagedTunName("wlan1", "wlan1"))
        assertTrue(TunManager.isManagedTunName("wlan1a1", "wlan1"))
        assertFalse(TunManager.isManagedTunName("wlan2", "wlan1"))
    }

    @Test
    fun `TUN setup assigns IPv6 address when provided`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            when {
                command.startsWith("ip link show") -> successfulCommand(output = "wlan1")
                command.startsWith("ip -6 addr show") -> successfulCommand(
                    output = "    inet6 fd10:10:14::1/64 scope global nodad",
                )
                else -> successfulCommand()
            }
        }

        val result = manager.configureTun(
            tunName = "wlan1",
            addressCidr = TunManager.DEFAULT_TUN_ADDRESS_CIDR,
            ipv6AddressCidr = TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR,
        )

        assertTrue(result.success)
        assertEquals(3, commands.size)
        assertTrue(commands[1].contains("ip -6 addr replace 'fd10:10:14::1/64' dev 'wlan1' nodad"))
        assertEquals("ip -6 addr show dev 'wlan1'", commands[2])
    }

    @Test
    fun `TUN setup leaves IPv6 untouched when no IPv6 address is provided`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            successfulCommand(output = "wlan1")
        }

        val result = manager.configureTun(tunName = "wlan1")

        assertTrue(result.success)
        assertEquals(2, commands.size)
        assertTrue("ip -6" !in commands[1])
    }

    @Test
    fun `TUN setup rejects an IPv6 address that remains tentative`() = runTest {
        val manager = TunManager { command ->
            when {
                command.startsWith("ip link show") -> successfulCommand(output = "wlan1")
                command.startsWith("ip -6 addr show") -> successfulCommand(
                    output = "    inet6 fd10:10:14::1/64 scope global tentative",
                )
                else -> successfulCommand()
            }
        }

        val result = manager.configureTun(
            tunName = "wlan1",
            ipv6AddressCidr = TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR,
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains(TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR))
    }

    @Test
    fun `app TUN IPv6 addresses use distinct bounded subnets`() {
        assertEquals("fd10:10:14:1::1/64", TunManager.appTunIpv6AddressCidr(1))
        assertEquals("fd10:10:14:fe::1/64", TunManager.appTunIpv6AddressCidr(254))
        assertEquals(TunManager.appTunIpv6AddressCidr(1), TunManager.appTunIpv6AddressCidr(0))
        assertEquals(TunManager.appTunIpv6AddressCidr(254), TunManager.appTunIpv6AddressCidr(255))
    }

    @Test
    fun `IPv6 route uses TUN when IPv6 is allowed`() {
        assertEquals(
            "ip -6 route replace default dev wlan1a1 table 110",
            TunManager.ipv6TunRouteCommand("wlan1a1", 110, allowIpv6 = true),
        )
    }

    @Test
    fun `IPv6 route fails closed when IPv6 is disabled`() {
        assertEquals(
            "ip -6 route replace unreachable default table 110",
            TunManager.ipv6TunRouteCommand("wlan1a1", 110, allowIpv6 = false),
        )
    }

    @Test
    fun `routing update guards both address families until complete policy is installed`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            successfulCommand()
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = setOf(10_001),
            appTunRoutes = listOf(TunManager.AppTunRoute("wlan1a1", 110, setOf(10_002))),
        )

        assertTrue(result.success)
        assertEquals(9, commands.size)
        assertEquals(
            "ip route replace unreachable default table 102 && ip -6 route replace unreachable default table 102",
            commands[0],
        )
        assertTrue(commands[1].contains("rule add iif lo uidrange 10000-10000 table 102 prio 11999"))
        assertTrue(commands[1].contains("rule add iif lo uidrange 10002-99999 table 102 prio 11999"))
        assertTrue(commands[1].contains("| ip -batch -"))
        assertTrue(commands[2].contains("rule add iif lo uidrange 10000-10000 table 102 prio 11999"))
        assertTrue(commands[2].contains("rule add iif lo uidrange 10002-99999 table 102 prio 11999"))
        assertTrue(commands[2].contains("| ip -6 -batch -"))
        assertTrue(commands[3].contains("ip -6 route replace unreachable default table 100"))
        assertTrue(commands[3].contains("ip -6 route replace unreachable default table 110"))
        assertTrue(commands[4].contains("rule add iif lo uidrange 10000-10000 table 100 prio 12010"))
        assertTrue(commands[4].contains("rule add iif lo uidrange 10002-10002 table 110 prio 12000"))
        assertTrue(commands[4].contains("| ip -batch -"))
        assertTrue(commands[5].contains("rule add iif lo uidrange 10000-10000 table 100 prio 12010"))
        assertTrue(commands[5].contains("rule add iif lo uidrange 10002-10002 table 110 prio 12000"))
        assertTrue(commands[5].contains("| ip -6 -batch -"))
        assertEquals("ip rule show table 102", commands[6])
        assertEquals("ip -6 rule show table 102", commands[7])
        assertTrue(commands[8].contains("ip route flush table 102"))
        assertTrue(commands[8].contains("ip -6 route flush table 102"))
        assertTrue(commands[3].contains("if ip route show table 100"))
        assertTrue(commands[3].contains("if ip -6 route show table 100"))
        commands.forEach { command ->
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
        }
    }

    @Test
    fun `routing guard removal deletes exact rules in bounded batches`() = runTest {
        val commands = mutableListOf<String>()
        var ipv4GuardInstalled = true
        var ipv6GuardInstalled = true
        val guardRules = (10_000..10_128).joinToString("\n") { uid ->
            "11999:\tfrom all iif lo uidrange $uid-$uid lookup 102"
        }
        val manager = TunManager { command ->
            commands += command
            when {
                command == "ip rule show table 102" -> successfulCommand(
                    if (ipv4GuardInstalled) guardRules else "",
                )
                command == "ip -6 rule show table 102" -> successfulCommand(
                    if (ipv6GuardInstalled) guardRules else "",
                )
                command.contains("rule del pref 11999 from all iif lo uidrange 10000-10000 lookup 102") &&
                    command.contains("| ip -6 -force -batch -") -> {
                    ipv6GuardInstalled = false
                    successfulCommand()
                }
                command.contains("rule del pref 11999 from all iif lo uidrange 10000-10000 lookup 102") &&
                    command.contains("| ip -force -batch -") -> {
                    ipv4GuardInstalled = false
                    successfulCommand()
                }
                else -> successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertTrue(result.success)
        assertTrue(
            commands.any {
                it.contains("rule del pref 11999 from all iif lo uidrange 10000-10000 lookup 102") &&
                    it.contains("| ip -force -batch -")
            },
        )
        assertTrue(
            commands.any {
                it.contains("rule del pref 11999 from all iif lo uidrange 10000-10000 lookup 102") &&
                    it.contains("| ip -6 -force -batch -")
            },
        )
        val deletionBatches = commands.filter { it.contains("rule del pref 11999") }
        assertEquals(4, deletionBatches.size)
        assertTrue(deletionBatches.all { it.contains("-force") })
        assertTrue(deletionBatches.all { command -> Regex("rule del pref 11999").findAll(command).count() <= 128 })
        assertEquals(258, deletionBatches.sumOf { command -> Regex("rule del pref 11999").findAll(command).count() })
        commands.forEach { command ->
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
        }
    }

    @Test
    fun `routing guard removal retries and fails closed when guards remain`() = runTest {
        val commands = mutableListOf<String>()
        val guardRule = "11999:\tfrom all iif lo uidrange 10000-99999 lookup 102"
        val manager = TunManager { command ->
            commands += command
            when (command) {
                "ip rule show table 102", "ip -6 rule show table 102" -> successfulCommand(guardRule)
                else -> successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("guard removal verification failed"))
        assertEquals(4, commands.count { it.contains("rule del pref 11999") })
        assertFalse(commands.any { it.contains("route flush table 102") })
    }

    @Test
    fun `routing guard inspection failure stops without deleting or flushing`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command == "ip rule show table 102") {
                RootShell.Result(1, "", "inspection failed")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("inspection failed"))
        assertFalse(commands.any { it.contains("rule del pref 11999") })
        assertFalse(commands.any { it.contains("route flush table 102") })
    }

    @Test
    fun `routing guard inspection ignores unrelated rules`() = runTest {
        val commands = mutableListOf<String>()
        val unrelatedRules = """
            12010:\tfrom all iif lo uidrange 10000-99999 lookup 100
            11999:\tfrom all iif lo uidrange 10000-99999 lookup 999
        """.trimIndent()
        val manager = TunManager { command ->
            commands += command
            when (command) {
                "ip rule show table 102", "ip -6 rule show table 102" -> successfulCommand(unrelatedRules)
                else -> successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertTrue(result.success)
        assertFalse(commands.any { it.contains("rule del pref 11999") })
    }

    @Test
    fun `routing guard removal recognizes named kernel route tables`() = runTest {
        val commands = mutableListOf<String>()
        var guardInstalled = true
        val manager = TunManager { command ->
            commands += command
            when {
                command == "ip rule show table 253" || command == "ip -6 rule show table 253" -> successfulCommand(
                    if (guardInstalled) "11999:\tfrom all iif lo uidrange 10000-99999 lookup default" else "",
                )
                command.contains("rule del pref 11999") -> {
                    guardInstalled = false
                    successfulCommand()
                }
                else -> successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 251,
            bypassTable = 252,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertTrue(result.success)
        assertTrue(commands.any { it.contains("rule del pref 11999") && it.contains("lookup default") })
    }

    @Test
    fun `physical bypass update replaces only its default route`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            successfulCommand()
        }

        val result = manager.replacePhysicalBypassRoute(
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.2", "wlan0"),
        )

        assertTrue(result.success)
        assertEquals(
            listOf("ip route replace default via 192.0.2.2 dev wlan0 table 101"),
            commands,
        )
    }

    @Test
    fun `failed routing update retains both address family guards`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (commands.size == 5) RootShell.Result(1, "", "rule failed") else successfulCommand()
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertEquals(5, commands.size)
        assertTrue(commands[0].contains("ip route replace unreachable default table 102"))
        assertTrue(commands[0].contains("ip -6 route replace unreachable default table 102"))
        assertTrue(commands[1].contains("table 102 prio 11999"))
        assertTrue(commands[2].contains("table 102 prio 11999"))
        assertFalse(commands.any { it == "ip rule show table 102" })
    }

    @Test
    fun `failed stale rule deletion retains update guards`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command.contains("rules=\$(ip rule show")) {
                RootShell.Result(1, "", "stale rule deletion failed")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertEquals(4, commands.size)
        assertFalse(commands.any { it == "ip rule show table 102" })
    }

    @Test
    fun `failed guard route installation stops before adding rules`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            RootShell.Result(1, "", "guard failed")
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertEquals(1, commands.size)
        assertEquals(
            "ip route replace unreachable default table 102 && ip -6 route replace unreachable default table 102",
            commands.single(),
        )
    }

    @Test
    fun `routing cleanup removes IPv6 rules routes and update guard`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            successfulCommand()
        }

        manager.removeRouting(
            fwmark = 255,
            routeMark = 100,
            routeTable = 100,
            tunName = "wlan1",
            managedAppRouteCount = 1,
        )

        assertTrue(commands.contains("ip -6 rule show"))
        assertTrue(commands.any { it.contains("ip -6 route flush table 100") })
        assertTrue(commands.any { it.contains("ip -6 route flush table 102") })
    }

    @Test
    fun `routing cleanup reports an inspection failure`() = runTest {
        val manager = TunManager { command ->
            if (command == "ip rule show") RootShell.Result(1, "", "unavailable") else successfulCommand()
        }

        val cleaned = manager.removeRouting(
            fwmark = 255,
            routeMark = 100,
            routeTable = 100,
            tunName = "wlan1",
            managedAppRouteCount = 1,
        )

        assertFalse(cleaned)
    }

    @Test
    fun `link cleanup tolerates interface disappearing after inspection`() {
        val command = managedLinkRemovalCommand(listOf("xray0"))
        val fakeIp = """
            present=1
            ip() {
                if [ "${'$'}1 ${'$'}2" = "link show" ]; then [ "${'$'}present" -eq 1 ]; return; fi
                if [ "${'$'}1 ${'$'}2" = "link del" ]; then present=0; return 1; fi
                return 1
            }
        """.trimIndent()

        assertEquals(0, ProcessBuilder("sh", "-c", "$fakeIp\n$command").start().waitFor())
    }

    @Test
    fun `link cleanup reports deletion failure while interface remains`() {
        val command = managedLinkRemovalCommand(listOf("xray0"))
        val fakeIp = """
            ip() {
                if [ "${'$'}1 ${'$'}2" = "link show" ]; then return 0; fi
                if [ "${'$'}1 ${'$'}2" = "link del" ]; then return 1; fi
                return 1
            }
        """.trimIndent()

        assertEquals(1, ProcessBuilder("sh", "-c", "$fakeIp\n$command").start().waitFor())
    }

    private fun successfulCommand(output: String = "") = RootShell.Result(0, output, "")
}
