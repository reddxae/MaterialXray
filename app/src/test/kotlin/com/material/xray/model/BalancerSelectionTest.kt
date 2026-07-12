package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancerSelectionTest {

    @Test
    fun `primary balancer follows the last matching routing rule`() {
        val config = server(
            rawConfigJson = """
                {
                  "routing": {
                    "rules": [
                      {"inboundTag":["fallback"],"balancerTag":"secondary"},
                      {"network":"tcp,udp","balancerTag":"primary"}
                    ],
                    "balancers": [
                      {"tag":"primary","selector":["proxy"]},
                      {"tag":"secondary","selector":["fallback-"]}
                    ]
                  },
                  "outbounds": []
                }
            """.trimIndent(),
        )

        assertEquals("primary", config.primaryBalancerTag())
    }

    @Test
    fun `balancer outbound matches peer profile while ignoring its tag`() {
        val auto = server(
            rawConfigJson = """
                {
                  "outbounds": [
                    {"tag":"proxy","protocol":"vless","settings":{"address":"one.example"}},
                    {"tag":"proxy-2","protocol":"vless","settings":{"address":"two.example"}}
                  ]
                }
            """.trimIndent(),
        )
        val matchingPeer = server(
            rawConfigJson = """
                {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"two.example"}}]}
            """.trimIndent(),
        )
        val otherPeer = server(
            rawConfigJson = """
                {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"other.example"}}]}
            """.trimIndent(),
        )

        assertTrue(auto.matchesBalancerOutbound("proxy-2", matchingPeer))
        assertFalse(auto.matchesBalancerOutbound("proxy-2", otherPeer))
    }

    @Test
    fun `balancer outbound masks IPv4 and IPv6 address fallbacks`() {
        val auto = server(
            rawConfigJson = """
                {
                  "outbounds": [
                    {
                      "tag":"proxy",
                      "protocol":"vless",
                      "settings":{"vnext":[{"address":"125.10.20.46","port":443}]}
                    },
                    {
                      "tag":"proxy-2",
                      "protocol":"vless",
                      "settings":{"vnext":[{"address":"2001:db8:abcd:1234::1","port":443}]}
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("125.**.**.46", auto.maskedBalancerOutboundAddress("proxy"))
        assertEquals("2001:****:****:****:****:****:****:1", auto.maskedBalancerOutboundAddress("proxy-2"))
    }

    private fun server(rawConfigJson: String) = ServerConfig(
        protocol = Protocol.RAW,
        name = "Test",
        address = "",
        port = 0,
        password = "",
        rawConfigJson = rawConfigJson,
    )
}
