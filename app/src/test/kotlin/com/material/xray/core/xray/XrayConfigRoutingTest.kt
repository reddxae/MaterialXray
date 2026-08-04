package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.ServerConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigRoutingTest {

    @Test
    fun `buildDns uses system fallback and domestic servers for direct domains`() {
        val dns = buildDns(
            servers = "",
            domesticServers = "77.88.8.8, 77.88.8.1",
            routingRules = listOf(directRule()),
            bypassLan = true,
        )

        assertEquals("UseIPv4", dns.getValue("queryStrategy").jsonPrimitive.content)
        assertTrue("tag" !in dns)
        val servers = dns.getValue("servers").jsonArray
        assertEquals("localhost", servers.first().jsonPrimitive.content)
        val domesticServers = servers.drop(1).map { it.jsonObject }
        assertEquals(listOf("77.88.8.8", "77.88.8.1"), domesticServers.map { it["address"]!!.jsonPrimitive.content })
        domesticServers.forEach { server ->
            val domains = server.getValue("domains").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("geosite:private" in domains)
            assertTrue("domain:example" in domains)
            assertEquals("domestic-dns", server.getValue("tag").jsonPrimitive.content)
        }
    }

    @Test
    fun `buildDns tags configured default servers for routing`() {
        val dns = buildDns(servers = "1.1.1.1, 8.8.8.8, 77.88.8.8, 77.88.8.1")

        assertEquals("default-dns", dns.getValue("tag").jsonPrimitive.content)
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8", "77.88.8.8", "77.88.8.1"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns omits ipv4-only query strategy when ipv6 is allowed`() {
        val dns = buildDns(servers = "1.1.1.1", allowIpv6 = true)

        assertTrue("queryStrategy" !in dns)
        assertEquals(
            listOf("1.1.1.1", "2606:4700:4700::1111"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns maps common IPv4 resolvers to matching IPv6 resolvers`() {
        val dns = buildDns(
            servers = "1.0.0.1, 8.8.8.8, 9.9.9.9, 77.88.8.8",
            allowIpv6 = true,
        )

        assertEquals(
            listOf(
                "1.0.0.1",
                "8.8.8.8",
                "9.9.9.9",
                "77.88.8.8",
                "2606:4700:4700::1001",
                "2001:4860:4860::8888",
                "2620:fe::fe",
                "2a02:6b8::feed:0ff",
            ),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns appends Cloudflare IPv6 when IPv4 resolvers have no mapping`() {
        val dns = buildDns(servers = "192.0.2.53, 198.51.100.53", allowIpv6 = true)

        assertEquals(
            listOf("192.0.2.53", "198.51.100.53", "2606:4700:4700::1111"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns preserves entered IPv6 resolvers without adding a fallback`() {
        val dns = buildDns(
            servers = "1.1.1.1, 2606:4700:4700::1001, 2a02:6b8::feed:0ff",
            allowIpv6 = true,
        )

        assertEquals(
            listOf("1.1.1.1", "2606:4700:4700::1001", "2a02:6b8::feed:0ff"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns drops entered IPv6 resolvers when IPv6 is disabled`() {
        val dns = buildDns(
            servers = "1.1.1.1, 2606:4700:4700::1001, [2a02:6b8::feed:0ff]:53, [fe80::1%wlan0]",
            allowIpv6 = false,
        )

        assertEquals(
            listOf("1.1.1.1"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns augments domestic IPv4 resolvers when IPv6 is allowed`() {
        val dns = buildDns(
            servers = "1.1.1.1",
            domesticServers = "77.88.8.8, 77.88.8.1",
            routingRules = listOf(directRule()),
            allowIpv6 = true,
        )

        val domesticServers = dns.getValue("servers").jsonArray.mapNotNull { it as? JsonObject }
        assertEquals(
            listOf(
                "77.88.8.8",
                "77.88.8.1",
                "2a02:6b8::feed:0ff",
                "2a02:6b8:0:1::feed:0ff",
            ),
            domesticServers.map { it.getValue("address").jsonPrimitive.content },
        )
    }

    @Test
    fun `buildRouting adds dns app lan custom and apply-rules routes in order`() {
        val routing = buildRouting(
            routingRules = listOf(orRule()),
            appProxyRoutes = listOf(
                appProxyRoute(inboundTag = "app-in-direct", outboundTag = "app-proxy-direct", applyRoutingRules = false),
                appProxyRoute(inboundTag = "app-in-rules", outboundTag = "proxy", applyRoutingRules = true),
            ),
            bypassLan = true,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "77.88.8.8",
        )

        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("IPOnDemand", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals(listOf("tun-in", "app-in-direct", "app-in-rules"), rules[0].array("inboundTag"))
        assertEquals("dns-out", rules[0].getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("tun-in", "app-in-direct", "app-in-rules"), rules[1].array("inboundTag"))
        assertEquals("853", rules[1].getValue("port").jsonPrimitive.content)
        assertEquals("tcp", rules[1].getValue("network").jsonPrimitive.content)
        assertEquals("direct", rules[1].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("default-dns", rules[2].array("inboundTag").single())
        assertEquals("direct", rules[2].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("domestic-dns", rules[3].array("inboundTag").single())
        assertEquals("app-in-direct", rules[4].array("inboundTag").single())
        assertEquals("geoip:private", rules[5].array("ip").single())
        assertEquals("geosite:private", rules[6].array("domain").single())
        assertEquals(listOf("domain:one", "domain:two"), rules[7].array("domain"))
        assertEquals(listOf("geoip:one"), rules[8].array("ip"))
        assertEquals("443", rules[9].getValue("port").jsonPrimitive.content)
        assertEquals(listOf("tcp", "udp"), rules[10].array("protocol"))
        assertEquals("app-in-rules", rules.last().array("inboundTag").single())
    }

    @Test
    fun `buildRouting applies provider domain settings`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            domainStrategy = "IPIfNonMatch",
            domainMatcher = "hybrid",
        )

        assertEquals("IPIfNonMatch", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals("hybrid", routing.getValue("domainMatcher").jsonPrimitive.content)
    }

    @Test
    fun `buildRouting omits direct upstream rules for disabled IPv6-only DNS`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            dnsServers = "2606:4700:4700::1111",
            domesticDnsServers = "2a02:6b8::feed:0ff",
            allowIpv6 = false,
        )

        val inboundTags = routing.getValue("rules").jsonArray.mapNotNull { rule ->
            rule.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content
        }
        assertTrue("default-dns" !in inboundTags)
        assertTrue("domestic-dns" !in inboundTags)
    }

    @Test
    fun `buildRouting omits domestic upstream rule without direct domains`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            bypassLan = false,
            domesticDnsServers = "77.88.8.8",
        )

        val inboundTags = routing.getValue("rules").jsonArray.mapNotNull { rule ->
            rule.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content
        }
        assertTrue("domestic-dns" !in inboundTags)
    }

    private fun JsonObject.array(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun directRule() = RoutingRule(
        id = "direct",
        name = "Direct",
        outboundTag = "direct",
        domains = listOf("domain:example"),
    )

    private fun orRule() = RoutingRule(
        id = "or",
        name = "OR",
        outboundTag = "direct",
        domains = listOf("domain:one", " domain:two "),
        ips = listOf("geoip:one"),
        port = "443",
        protocols = listOf("tcp", "udp"),
        operator = RoutingRuleOperator.OR,
    )

    private fun appProxyRoute(
        inboundTag: String,
        outboundTag: String,
        applyRoutingRules: Boolean,
    ) = AppProxyRoute(
        inboundTag = inboundTag,
        tunName = "$inboundTag-tun",
        outboundTag = outboundTag,
        server = ServerConfig(
            protocol = Protocol.VLESS,
            name = inboundTag,
            address = "203.0.113.8",
            port = 443,
            password = "uuid",
        ),
        applyRoutingRules = applyRoutingRules,
    )
}
