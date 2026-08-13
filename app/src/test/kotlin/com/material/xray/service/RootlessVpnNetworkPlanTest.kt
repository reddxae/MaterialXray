package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RootlessVpnNetworkPlanTest {

    @Test
    fun `IPv6 disabled keeps rootless VPN IPv4-only and fail-closed`() {
        val plan = planRootlessVpnNetwork(allowIpv6 = false)

        assertEquals(
            listOf(VpnNetworkPrefix(address = "10.10.14.1", prefixLength = 30)),
            plan.addresses,
        )
        assertEquals(
            listOf(VpnNetworkPrefix(address = "0.0.0.0", prefixLength = 0)),
            plan.routes,
        )
        assertEquals(listOf("10.10.14.2"), plan.dnsServers)
        assertEquals("10.10.14.2", plan.syntheticDnsAddress)
    }

    @Test
    fun `IPv6 enabled assigns an address and captures the default IPv6 route`() {
        val plan = planRootlessVpnNetwork(allowIpv6 = true)

        assertEquals(
            listOf(
                VpnNetworkPrefix(address = "10.10.14.1", prefixLength = 30),
                VpnNetworkPrefix(address = "fd10:10:14::1", prefixLength = 64),
            ),
            plan.addresses,
        )
        assertEquals(
            listOf(
                VpnNetworkPrefix(address = "0.0.0.0", prefixLength = 0),
                VpnNetworkPrefix(address = "::", prefixLength = 0),
            ),
            plan.routes,
        )
        assertEquals(listOf("10.10.14.2"), plan.dnsServers)
        assertEquals("10.10.14.2", plan.syntheticDnsAddress)
    }
}
