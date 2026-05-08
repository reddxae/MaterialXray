package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            assertEquals("All outbounds must resolve domains through xray DNS", "UseIPv4", domainStrategy)
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
    fun `uses proxy as default outbound`() {
        val config = generator.generate(vlessReality)
        val json = Json.parseToJsonElement(config).jsonObject
        val firstOutbound = json["outbounds"]!!.jsonArray.first().jsonObject

        assertEquals("proxy", firstOutbound["tag"]?.jsonPrimitive?.content)
    }

    @Test
    fun `puts configured default outbound first`() {
        val config = generator.generate(vlessReality, defaultOutbound = XrayOutbound.Direct)
        val json = Json.parseToJsonElement(config).jsonObject
        val outboundTags = json["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]?.jsonPrimitive?.content }

        assertEquals("direct", outboundTags.first())
        assertTrue("Proxy outbound must remain available for routing rules", "proxy" in outboundTags)
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
    fun `uses system DNS when DNS servers are empty`() {
        val config = generator.generate(vlessReality, dnsServers = "")
        val json = Json.parseToJsonElement(config).jsonObject
        val servers = json["dns"]!!.jsonObject["servers"]!!.jsonArray

        assertEquals(listOf("localhost"), servers.map { it.jsonPrimitive.content })
    }

    @Test
    fun `adds domestic DNS for direct domains and routes it directly`() {
        val config = generator.generate(
            vlessReality,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "77.88.8.8,77.88.8.1",
            routingRules = RoutingRuleCatalog.defaults(),
        )
        val json = Json.parseToJsonElement(config).jsonObject

        val servers = json["dns"]!!.jsonObject["servers"]!!.jsonArray
        val domesticServers = servers.mapNotNull { it as? JsonObject }
            .filter { it["tag"]?.jsonPrimitive?.content == "domestic-dns" }
        assertEquals(2, domesticServers.size)
        assertEquals("77.88.8.8", domesticServers.first()["address"]!!.jsonPrimitive.content)
        val domesticDomains = domesticServers.first()["domains"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(domesticDomains.contains("geosite:private"))
        assertTrue(domesticDomains.contains("domain:ru"))
        assertTrue(domesticDomains.contains("geosite:category-ru"))
        assertTrue(domesticServers.first()["skipFallback"]!!.jsonPrimitive.boolean)

        val domesticDnsRoute = json["routing"]!!.jsonObject["rules"]!!.jsonArray.firstOrNull {
            it.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domestic-dns"
        }
        assertNotNull("Domestic DNS queries should be routed directly", domesticDnsRoute)
        assertEquals("direct", domesticDnsRoute!!.jsonObject["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `includes enabled built in routing rules`() {
        val routingRules = RoutingRuleCatalog.defaults()
        val config = generator.generate(vlessReality, routingRules = routingRules)
        val json = Json.parseToJsonElement(config).jsonObject
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray

        val lanIpRule = rules.firstOrNull {
            it.jsonObject["ip"]?.jsonArray?.any { ip ->
                ip.jsonPrimitive.content == "geoip:private"
            } == true
        }
        val lanDomainRule = rules.firstOrNull {
            it.jsonObject["domain"]?.jsonArray?.any { domain ->
                domain.jsonPrimitive.content == "geosite:private"
            } == true
        }
        val adsRule = rules.firstOrNull {
            it.jsonObject["domain"]?.jsonArray?.any { domain ->
                domain.jsonPrimitive.content == "geosite:category-ads-all"
            } == true
        }
        val defaultProxyRule = rules.firstOrNull {
            it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "proxy" &&
                it.jsonObject["port"]?.jsonPrimitive?.content == "0-65535"
        }

        assertNotNull("Should include enabled LAN IP direct rule", lanIpRule)
        assertNotNull("Should include enabled LAN domain direct rule", lanDomainRule)
        assertNull("Block Ads should be disabled by default", adsRule)
        assertNull("Default Proxy preset should not be emitted", defaultProxyRule)
    }

    @Test
    fun `omits lan direct rules when bypass lan is disabled`() {
        val config = generator.generate(vlessReality, bypassLan = false)
        val json = Json.parseToJsonElement(config).jsonObject
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray

        val lanIpRule = rules.firstOrNull {
            it.jsonObject["ip"]?.jsonArray?.any { ip ->
                ip.jsonPrimitive.content == "geoip:private"
            } == true
        }
        val lanDomainRule = rules.firstOrNull {
            it.jsonObject["domain"]?.jsonArray?.any { domain ->
                domain.jsonPrimitive.content == "geosite:private"
            } == true
        }

        assertNull("LAN IP direct rule should be disabled by setting", lanIpRule)
        assertNull("LAN domain direct rule should be disabled by setting", lanDomainRule)
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

    @Test
    fun `adds app specific tun inbound outbound and route`() {
        val appServer = vlessReality.copy(name = "Apps", address = "5.6.7.8")
        val config = generator.generate(
            vlessReality,
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-42",
                    tunName = "xray0a1",
                    outboundTag = "app-proxy-42",
                    server = appServer,
                )
            ),
        )
        val json = Json.parseToJsonElement(config).jsonObject

        val appInbound = json["inbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "app-in-42"
        }.jsonObject
        assertEquals("xray0a1", appInbound["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val appOutbound = json["outbounds"]!!.jsonArray.first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "app-proxy-42"
        }.jsonObject
        assertEquals("5.6.7.8", appOutbound["settings"]!!.jsonObject["vnext"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)

        val appRoute = json["routing"]!!.jsonObject["rules"]!!.jsonArray.first {
            it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "app-proxy-42"
        }.jsonObject
        assertEquals("app-in-42", appRoute["inboundTag"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `routes default selected app traffic through user rules before proxy fallback`() {
        val appServer = vlessReality.copy(name = "Default selected", address = "5.6.7.8")
        val config = generator.generate(
            vlessReality,
            defaultOutbound = XrayOutbound.Direct,
            routingRules = RoutingRuleCatalog.defaults(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-default-selected",
                    tunName = "xray0a1",
                    outboundTag = "proxy",
                    server = appServer,
                    applyRoutingRules = true,
                )
            ),
        )
        val json = Json.parseToJsonElement(config).jsonObject
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        assertNull(
            "Default selected app route should reuse the main proxy outbound instead of adding a duplicate app outbound",
            json["outbounds"]!!.jsonArray.firstOrNull {
                it.jsonObject["tag"]?.jsonPrimitive?.content == "app-proxy-default-selected"
            },
        )
        val ruRuleIndex = rules.indexOfFirst {
            it["domain"]?.jsonArray?.any { domain -> domain.jsonPrimitive.content == "domain:ru" } == true
        }
        val fallbackIndex = rules.indexOfFirst {
            it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "app-in-default-selected" &&
                it["outboundTag"]?.jsonPrimitive?.content == "proxy"
        }

        assertTrue("User routing rules should be emitted before default selected proxy fallback", ruRuleIndex in 0 until fallbackIndex)
    }

}
