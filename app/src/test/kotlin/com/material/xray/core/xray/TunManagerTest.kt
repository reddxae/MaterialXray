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
    fun `routing update guards IPv6 until complete policy is installed`() = runTest {
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
        assertEquals(3, commands.size)
        assertTrue(commands[0].startsWith("ip -6 route replace unreachable default table 102"))
        assertTrue(commands[0].contains("ip -6 rule add iif lo uidrange 10000-10000 table 102 prio 11999"))
        assertTrue(commands[0].contains("ip -6 rule add iif lo uidrange 10002-99999 table 102 prio 11999"))
        assertFalse(commands[0].contains("uidrange 10001-10001 table 102"))
        assertTrue(commands[0].contains("ip -6 route replace unreachable default table 100"))
        assertTrue(commands[0].contains("ip -6 route replace unreachable default table 110"))
        assertTrue(commands[1].contains("ip rule add iif lo uidrange 10000-10000 table 100 prio 12010"))
        assertTrue(commands[1].contains("ip -6 rule add iif lo uidrange 10000-10000 table 100 prio 12010"))
        assertTrue(commands[1].contains("ip rule add iif lo uidrange 10002-10002 table 110 prio 12000"))
        assertTrue(commands[1].contains("ip -6 rule add iif lo uidrange 10002-10002 table 110 prio 12000"))
        assertTrue(commands[2].contains("ip -6 rule del table 102 pref 11999"))
        assertTrue(commands[2].contains("lookup 102"))
        assertTrue(commands[2].contains("ip -6 route flush table 102"))
    }

    @Test
    fun `failed routing update retains IPv6 guard`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (commands.size == 2) RootShell.Result(1, "", "rule failed") else successfulCommand()
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
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("table 102 prio 11999"))
        assertFalse(commands.any { it.contains("grep -Eq") })
    }

    @Test
    fun `failed guard installation does not remove an existing guard`() = runTest {
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
        assertFalse(commands.single().contains("rule del pref 11999"))
        assertTrue(commands.single().contains("lookup 102' || ip -6 rule add"))
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

    private fun successfulCommand() = RootShell.Result(0, "", "")
}
