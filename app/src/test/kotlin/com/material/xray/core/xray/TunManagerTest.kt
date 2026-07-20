package com.material.xray.core.xray

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
}
