package com.materialxray.core.xray

import com.materialxray.model.Protocol
import com.materialxray.model.RoutingRuleCatalog
import com.materialxray.model.ServerConfig
import com.materialxray.model.XrayLogLevel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConfigGeneratorTest {

    private val generator = ConfigGenerator()

    private val vlessReality = ServerConfig(
        protocol = Protocol.VLESS,
        name = "Test",
        address = "1.2.3.4",
        port = 443,
        password = "test-uuid",
        transport = ServerConfig.Transport(type = "tcp"),
        security = ServerConfig.Security(
            type = "reality", sni = "example.com",
            fingerprint = "chrome", publicKey = "testpbk",
        ),
        extra = mapOf("flow" to "xtls-rprx-vision", "encryption" to "none"),
    )

    @Test
    fun `generates TUN inbound with correct name and MTU`() {
        val config = generator.generate(vlessReality, tunName = "wlan2", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val inbounds = json["inbounds"]!!.jsonArray
        val tun = inbounds.first { it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun" }.jsonObject
        assertEquals(0, tun["port"]?.jsonPrimitive?.int)
        val settings = tun["settings"]!!.jsonObject
        assertEquals("wlan2", settings["name"]?.jsonPrimitive?.content)
        assertEquals(1500, settings["MTU"]?.jsonPrimitive?.int)
    }

    @Test
    fun `enables sniffing on the TUN inbound for routing`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val tun = json["inbounds"]!!.jsonArray.first {
            it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun"
        }.jsonObject
        val sniffing = tun["sniffing"]!!.jsonObject

        assertTrue(sniffing["enabled"]!!.jsonPrimitive.boolean)
        assertTrue(sniffing["routeOnly"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("http", "tls", "quic"),
            sniffing["destOverride"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `sets fwmark on all outbounds`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val outbounds = json["outbounds"]!!.jsonArray
        for (ob in outbounds) {
            if (ob.jsonObject["protocol"]?.jsonPrimitive?.content == "blackhole") continue
            val mark = ob.jsonObject["streamSettings"]?.jsonObject
                ?.get("sockopt")?.jsonObject?.get("mark")?.jsonPrimitive?.int
            assertEquals("All outbounds must have fwmark", 255, mark)
        }
    }

    @Test
    fun `sets outbound domain resolution to xray dns`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val outbounds = json["outbounds"]!!.jsonArray
        for (ob in outbounds) {
            if (ob.jsonObject["protocol"]?.jsonPrimitive?.content == "blackhole") continue
            val domainStrategy = ob.jsonObject["streamSettings"]?.jsonObject
                ?.get("sockopt")?.jsonObject?.get("domainStrategy")?.jsonPrimitive?.content
            assertEquals("All outbounds must resolve domains through xray DNS", "UseIP", domainStrategy)
        }
    }

    @Test
    fun `uses error as the default xray log level`() {
        val config = generator.generate(vlessReality)
        val json = Json.parseToJsonElement(config).jsonObject

        assertEquals("none", json["log"]!!.jsonObject["access"]!!.jsonPrimitive.content)
        assertEquals("error", json["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `uses the configured xray log level`() {
        val config = generator.generate(vlessReality, logLevel = XrayLogLevel.Debug)
        val json = Json.parseToJsonElement(config).jsonObject

        assertEquals("debug", json["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generates VLESS REALITY outbound correctly`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255)
        val json = Json.parseToJsonElement(config).jsonObject
        val proxy = json["outbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy"
        }.jsonObject
        assertEquals("vless", proxy["protocol"]?.jsonPrimitive?.content)
        val vnext = proxy["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject
        assertEquals("1.2.3.4", vnext["address"]?.jsonPrimitive?.content)
        assertEquals(443, vnext["port"]?.jsonPrimitive?.int)
        val user = vnext["users"]!!.jsonArray[0].jsonObject
        assertEquals("test-uuid", user["id"]?.jsonPrimitive?.content)
        assertEquals("xtls-rprx-vision", user["flow"]?.jsonPrimitive?.content)
        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals("reality", stream["security"]?.jsonPrimitive?.content)
        val reality = stream["realitySettings"]!!.jsonObject
        assertEquals("example.com", reality["serverName"]?.jsonPrimitive?.content)
        assertEquals("testpbk", reality["publicKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `includes DNS routing rule for port 53`() {
        val config = generator.generate(vlessReality, tunName = "xray0", fwmark = 255, dnsServers = "1.1.1.1,8.8.8.8")
        val json = Json.parseToJsonElement(config).jsonObject
        assertNotNull(json["dns"])
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray
        val dnsRule = rules.firstOrNull { it.jsonObject["port"]?.jsonPrimitive?.content == "53" }
        assertNotNull("Should have DNS port 53 routing rule", dnsRule)
        val inboundTags = dnsRule!!.jsonObject["inboundTag"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("tun-in"), inboundTags)
    }

    @Test
    fun `includes enabled routing rules from catalog`() {
        val routingRules = RoutingRuleCatalog.defaults()
        val config = generator.generate(vlessReality, routingRules = routingRules)
        val json = Json.parseToJsonElement(config).jsonObject
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray

        val adsRule = rules.firstOrNull {
            it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "block"
        }
        assertNotNull("Should include enabled block outbound rule", adsRule)
        assertEquals(
            "geosite:category-ads-all",
            adsRule!!.jsonObject["domain"]!!.jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun `splits OR routing rule into separate xray rules`() {
        val routingRules = RoutingRuleCatalog.defaults()
        val config = generator.generate(vlessReality, routingRules = routingRules)
        val json = Json.parseToJsonElement(config).jsonObject
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray

        val ruDomainRule = rules.firstOrNull {
            it.jsonObject["domain"]?.jsonArray?.any { domain ->
                domain.jsonPrimitive.content == "domain:ru"
            } == true
        }
        val ruIpRule = rules.firstOrNull {
            it.jsonObject["ip"]?.jsonArray?.any { ip ->
                ip.jsonPrimitive.content == "geoip:ru"
            } == true
        }

        assertNotNull("RU direct OR rule should emit a domain-based rule", ruDomainRule)
        assertNotNull("RU direct OR rule should emit an IP-based rule", ruIpRule)
    }
}
