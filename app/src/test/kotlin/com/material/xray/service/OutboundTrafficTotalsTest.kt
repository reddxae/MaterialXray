package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboundTrafficTotalsTest {
    @Test
    fun `an empty stats answer yields no reading at all`() {
        assertNull(emptyMap<String, Long>().readOutboundTraffic())
        assertNull(mapOf("inbound>>>tun-in>>>traffic>>>uplink" to 10L).readOutboundTraffic())
    }

    @Test
    fun `proxy and direct counters are kept apart`() {
        val totals = mapOf(
            "outbound>>>proxy>>>traffic>>>uplink" to 100L,
            "outbound>>>proxy>>>traffic>>>downlink" to 900L,
            "outbound>>>direct>>>traffic>>>uplink" to 5L,
            "outbound>>>direct>>>traffic>>>downlink" to 15L,
        ).readOutboundTraffic()

        assertEquals(100L, totals?.proxyUplinkBytes)
        assertEquals(900L, totals?.proxyDownlinkBytes)
        assertEquals(1_000L, totals?.proxyBytes)
        assertEquals(20L, totals?.directBytes)
    }

    @Test
    fun `every outbound a raw config named itself counts as proxied`() {
        val totals = mapOf(
            "outbound>>>tokyo-01>>>traffic>>>uplink" to 10L,
            "outbound>>>tokyo-02>>>traffic>>>uplink" to 20L,
            "outbound>>>tokyo-02>>>traffic>>>downlink" to 40L,
        ).readOutboundTraffic()

        assertEquals(30L, totals?.proxyUplinkBytes)
        assertEquals(40L, totals?.proxyDownlinkBytes)
        assertEquals(0L, totals?.directBytes)
    }

    @Test
    fun `the core's own service outbounds are left out of both totals`() {
        val totals = mapOf(
            "outbound>>>proxy>>>traffic>>>uplink" to 10L,
            "outbound>>>dns-out>>>traffic>>>uplink" to 1_000L,
            "outbound>>>block>>>traffic>>>downlink" to 2_000L,
        ).readOutboundTraffic()

        assertEquals(10L, totals?.proxyBytes)
        assertEquals(0L, totals?.directBytes)
    }

    @Test
    fun `counters that are not traffic are ignored`() {
        val totals = mapOf(
            "outbound>>>proxy>>>traffic>>>uplink" to 10L,
            "outbound>>>proxy>>>sessions>>>uplink" to 99L,
            "outbound>>>proxy>>>traffic>>>sideways" to 99L,
        ).readOutboundTraffic()

        assertEquals(10L, totals?.proxyBytes)
    }
}
