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
        assertEquals(
            listOf("77.88.8.8", "77.88.8.1"),
            domesticServers.map { it["address"]!!.jsonPrimitive.content },
        )
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
            listOf("https://1.1.1.1/dns-query", "8.8.8.8", "77.88.8.8", "77.88.8.1"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns preserves explicit DNS endpoint URLs`() {
        val dns = buildDns(servers = "https://dns.quad9.net/dns-query,tcp://9.9.9.9:53")

        assertEquals(
            listOf("https://dns.quad9.net/dns-query", "tcp://9.9.9.9:53"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns omits ipv4-only query strategy when ipv6 is allowed`() {
        val dns = buildDns(servers = "1.1.1.1", allowIpv6 = true)

        assertTrue("queryStrategy" !in dns)
        assertEquals(
            listOf("https://1.1.1.1/dns-query"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns uses bracketed DNS-over-HTTPS endpoints for Cloudflare IPv6 resolvers`() {
        val dns = buildDns(servers = "2606:4700:4700::1111, [2a02:6b8::feed:0ff]:53", allowIpv6 = true)

        assertEquals(
            listOf("https://[2606:4700:4700::1111]/dns-query", "[2a02:6b8::feed:0ff]:53"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns preserves explicit ports when switching Cloudflare to DNS-over-TCP`() {
        val dns = buildDns(servers = "1.1.1.1:5353,8.8.8.8:5353")

        assertEquals(
            listOf("tcp://1.1.1.1:5353", "8.8.8.8:5353"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns includes pre-resolved proxy endpoint hosts`() {
        val dns = buildDns(
            servers = "1.1.1.1",
            bootstrapHosts = mapOf(
                "second.example" to listOf("2001:db8::2"),
                "first.example" to listOf("192.0.2.1", "192.0.2.1"),
            ),
        )

        val hosts = dns.getValue("hosts").jsonObject
        assertEquals(listOf("first.example", "second.example"), hosts.keys.toList())
        assertEquals(listOf("192.0.2.1"), hosts.getValue("first.example").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("2001:db8::2"), hosts.getValue("second.example").jsonArray.map { it.jsonPrimitive.content })
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
        assertEquals("proxy", rules[2].getValue("outboundTag").jsonPrimitive.content)
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
    fun `buildRouting routes default DNS through a balancer target`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            dnsServers = "1.1.1.1",
            defaultRouteTarget = XrayRouteTarget.Balancer("balance"),
        )

        val defaultDnsRule = routing.getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns" }
        assertEquals("balance", defaultDnsRule.getValue("balancerTag").jsonPrimitive.content)
        assertTrue("outboundTag" !in defaultDnsRule)
    }

    @Test
    fun `buildRouting routes apply-rules app fallback through default balancer`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                appProxyRoute(inboundTag = "app-in-default-selected", outboundTag = "proxy", applyRoutingRules = true),
            ),
            defaultRouteTarget = XrayRouteTarget.Balancer("balance"),
        )

        val appFallback = routing.getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "app-in-default-selected" }
        assertEquals("balance", appFallback.getValue("balancerTag").jsonPrimitive.content)
        assertTrue("outboundTag" !in appFallback)
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
