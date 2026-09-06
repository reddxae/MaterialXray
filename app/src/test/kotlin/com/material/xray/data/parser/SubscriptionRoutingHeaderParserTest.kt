package com.material.xray.data.parser

import com.material.xray.model.RoutingRuleOperator
import java.util.Base64
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRoutingHeaderParserTest {
    @Test
    fun `happ routing header converts site and ip buckets to OR rules`() {
        val payload = """
            {
              "Name": "RU Direct v2",
              "GlobalProxy": "true",
              "RouteOrder": "proxy-direct-block",
              "DirectSites": ["geosite:category-ru", "domain:ru", "domain:xn--p1ai"],
              "DirectIp": ["geoip:ru", "geoip:private"],
              "ProxySites": [],
              "ProxyIp": [],
              "BlockSites": [],
              "BlockIp": [],
              "DomainStrategy": "IPIfNonMatch"
            }
        """.trimIndent()
        val headers = Headers.headersOf("routing", happRoutingLink(payload))

        val routing = requireNotNull(SubscriptionRoutingHeaderParser.parse(headers))

        assertEquals("IPIfNonMatch", routing.domainStrategy)
        assertEquals("proxy", routing.fallbackOutboundTag)
        val rule = routing.rules.single()
        assertEquals("RU Direct v2: Direct", rule.name)
        assertEquals("direct", rule.outboundTag)
        assertEquals(listOf("geosite:category-ru", "domain:ru", "domain:xn--p1ai"), rule.domains)
        assertEquals(listOf("geoip:ru", "geoip:private"), rule.ips)
        assertEquals(RoutingRuleOperator.OR, rule.operator)
    }

    @Test
    fun `happ routing header preserves configured bucket order`() {
        val payload = """
            {
              "RouteOrder": "proxy-direct-block",
              "DirectSites": ["domain:direct.example"],
              "ProxySites": ["domain:proxy.example"],
              "ProxyIp": ["geoip:proxy"],
              "BlockSites": ["domain:block.example"],
              "BlockIp": ["geoip:block"]
            }
        """.trimIndent()
        val headers = Headers.headersOf("routing", happRoutingLink(payload, action = "onadd"))

        val routing = requireNotNull(SubscriptionRoutingHeaderParser.parse(headers))

        assertEquals(listOf("proxy", "direct", "block"), routing.rules.map { it.outboundTag })
        assertTrue(routing.rules.all { it.operator == RoutingRuleOperator.OR })
        assertEquals(listOf("domain:proxy.example"), routing.rules[0].domains)
        assertEquals(listOf("geoip:block"), routing.rules[2].ips)
        assertEquals("IPIfNonMatch", routing.domainStrategy)
        assertEquals("proxy", routing.fallbackOutboundTag)
    }

    @Test
    fun `happ routing header disables global proxy with direct fallback`() {
        val payload = """
            {
              "GlobalProxy": false,
              "DirectSites": ["domain:direct.example"],
              "DomainStrategy": "invalid"
            }
        """.trimIndent()
        val headers = Headers.headersOf("routing", happRoutingLink(payload))

        val routing = requireNotNull(SubscriptionRoutingHeaderParser.parse(headers))

        assertEquals("direct", routing.fallbackOutboundTag)
        assertEquals("IPIfNonMatch", routing.domainStrategy)
    }

    @Test
    fun `happ routing header ignores buckets containing only blank entries`() {
        val payload = """
            {
              "DirectSites": ["", "   "],
              "DirectIp": " "
            }
        """.trimIndent()
        val headers = Headers.headersOf("routing", happRoutingLink(payload))

        val routing = requireNotNull(SubscriptionRoutingHeaderParser.parse(headers))

        assertTrue(routing.rules.isEmpty())
    }

    @Test
    fun `invalid happ routing header is ignored`() {
        val headers = Headers.headersOf("routing", "happ://routing/add/not-base64")

        assertNull(SubscriptionRoutingHeaderParser.parse(headers))
    }

    @Test
    fun `routing-enable 0 suppresses routing import`() {
        val payload = """{ "DirectSites": ["domain:direct.example"] }""".trimIndent()
        val headers = Headers.headersOf(
            "routing",
            happRoutingLink(payload),
            "routing-enable",
            "0",
        )

        assertNull(SubscriptionRoutingHeaderParser.parse(headers))
    }

    @Test
    fun `routing-enable false suppresses routing import`() {
        val payload = """{ "DirectSites": ["domain:direct.example"] }""".trimIndent()
        val headers = Headers.headersOf(
            "routing",
            happRoutingLink(payload),
            "routing-enable",
            " false ",
        )

        assertNull(SubscriptionRoutingHeaderParser.parse(headers))
    }

    @Test
    fun `routing-enable enabled values keep routing import`() {
        val payload = """{ "DirectSites": ["domain:direct.example"] }""".trimIndent()
        listOf("1", "true", "yes", "").forEach { enableValue ->
            val headers = Headers.headersOf(
                "routing",
                happRoutingLink(payload),
                "routing-enable",
                enableValue,
            )

            assertEquals("proxy", SubscriptionRoutingHeaderParser.parse(headers)?.fallbackOutboundTag)
        }
    }

    private fun happRoutingLink(payload: String, action: String = "add"): String = "happ://routing/$action/${Base64.getEncoder().encodeToString(payload.toByteArray())}"
}
