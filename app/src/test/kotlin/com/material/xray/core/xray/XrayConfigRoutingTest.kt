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
    fun `buildDns omits ipv4-only query strategy when ipv6 is allowed`() {
        val dns = buildDns(servers = "1.1.1.1", allowIpv6 = true)

        assertTrue("queryStrategy" !in dns)
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
            domesticDnsServers = "77.88.8.8",
        )

        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("IPOnDemand", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals(listOf("tun-in", "app-in-direct", "app-in-rules"), rules[0].array("inboundTag"))
        assertEquals("dns-out", rules[0].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("domestic-dns", rules[1].array("inboundTag").single())
        assertEquals("app-in-direct", rules[2].array("inboundTag").single())
        assertEquals("geoip:private", rules[3].array("ip").single())
        assertEquals("geosite:private", rules[4].array("domain").single())
        assertEquals(listOf("domain:one", "domain:two"), rules[5].array("domain"))
        assertEquals(listOf("geoip:one"), rules[6].array("ip"))
        assertEquals("443", rules[7].getValue("port").jsonPrimitive.content)
        assertEquals(listOf("tcp", "udp"), rules[8].array("protocol"))
        assertEquals("app-in-rules", rules.last().array("inboundTag").single())
    }

    private fun JsonObject.array(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

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
