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
        assertEquals(7, commands.size)
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
        assertTrue(commands[6].contains("ip rule del pref 11999 table 102"))
        assertTrue(commands[6].contains("ip -6 rule del pref 11999 table 102"))
        assertTrue(commands[6].contains("ip route flush table 102"))
        assertTrue(commands[6].contains("ip -6 route flush table 102"))
        assertTrue(commands[3].contains("if ip route show table 100"))
        assertTrue(commands[3].contains("if ip -6 route show table 100"))
        commands.forEach { command ->
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
        }
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
        assertFalse(commands.any { it.contains("grep -Eq") })
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
        assertFalse(commands.any { it.contains("guard_rules=") })
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

    private fun successfulCommand() = RootShell.Result(0, "", "")
}
