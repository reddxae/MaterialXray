package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawConfigTunInjectorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val injector = RawConfigTunInjector(json)

    @Test
    fun `inject replaces provider inbounds with managed tun inbounds`() {
        val result = injector.inject(
            rawJson = """
                {
                  "inbounds": [
                    {"tag":"socks-in","listen":"127.0.0.1","port":10808,"protocol":"socks"},
                    {"tag":"http-in","listen":"127.0.0.1","port":10809,"protocol":"http"}
                  ],
                  "outbounds": [
                    {"protocol":"vless","settings":{}},
                    {"tag":"legacy-block","protocol":"blackhole"}
                  ]
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 255,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Debug,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = true,
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-7",
                    tunName = "xray0a1",
                    outboundTag = "app-proxy-7",
                    server = server("App route"),
                ),
            ),
            physicalInterface = "wlan0",
            xrayApiEndpoint = XrayApiEndpoint.LoopbackTcp(48_123),
            xrayBufferSizeKiB = 1024,
            tunMtu = 1400,
        )

        val root = json.parseToJsonElement(result).jsonObject
        assertEquals(
            listOf("StatsService", "RoutingService"),
            root.getValue("api").jsonObject.getValue("services").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("127.0.0.1:48123", root.getValue("api").jsonObject.getValue("listen").jsonPrimitive.content)
        val inbounds = root.getValue("inbounds").jsonArray
        assertEquals(listOf("tun-in", "app-in-7"), inbounds.map { it.jsonObject["tag"]!!.jsonPrimitive.content })
        assertTrue(inbounds.all { it.jsonObject["protocol"]!!.jsonPrimitive.content == "tun" })
        assertTrue(inbounds.none { "listen" in it.jsonObject })
        assertTrue(inbounds.all { it.jsonObject["port"]!!.jsonPrimitive.content == "0" })
        assertTrue(inbounds.all { it.jsonObject["settings"]!!.jsonObject["MTU"]!!.jsonPrimitive.content == "1400" })
        assertEquals("xray0", inbounds.first().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("xray0a1", inbounds.last().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            1024,
            root.getValue("policy").jsonObject
                .getValue("levels").jsonObject
                .getValue("0").jsonObject
                .getValue("bufferSize").jsonPrimitive.content.toInt(),
        )

        val outbounds = root.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("proxy", "app-proxy-7", "direct", "block", "dns-out", "legacy-block"),
            outbounds.map { it["tag"]!!.jsonPrimitive.content },
        )
        val proxySockopt = outbounds.first().getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
        assertEquals(255, proxySockopt.getValue("mark").jsonPrimitive.content.toInt())
        assertEquals("wlan0", proxySockopt.getValue("interface").jsonPrimitive.content)
        assertEquals("debug", root.getValue("log").jsonObject.getValue("loglevel").jsonPrimitive.content)
    }

    @Test
    fun `inject replaces provider tun inbound with app managed tun`() {
        val result = injector.inject(
            rawJson = """
                {
                  "inbounds": [{"tag":"tun-in","protocol":"tun","settings":{"name":"existing0"}}],
                  "outbounds": [{"tag":"proxy","protocol":"vless","settings":{}}]
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val inbounds = json.parseToJsonElement(result).jsonObject.getValue("inbounds").jsonArray
        assertEquals(1, inbounds.size)
        assertEquals("tun-in", inbounds.single().jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("xray0", inbounds.single().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inject routes default DNS through case-variant proxy tags`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [{"tag":"Proxy","protocol":"vless","settings":{}}]
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val root = json.parseToJsonElement(result).jsonObject
        val outboundTags = root.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        assertEquals(1, outboundTags.count { it.equals("proxy", ignoreCase = true) })
        assertEquals("Proxy", outboundTags.first())
        val defaultDnsRule = root.getValue("routing").jsonObject.getValue("rules").jsonArray.first {
            it.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns"
        }
        assertEquals("Proxy", defaultDnsRule.jsonObject.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `inject preserves raw routing for multi-outbound profiles`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [
                    {"tag":"proxy","protocol":"vless","settings":{}},
                    {"tag":"proxy-2","protocol":"vless","settings":{}},
                    {"tag":"direct","protocol":"freedom"},
                    {"tag":"block","protocol":"blackhole"}
                  ],
                  "routing": {
                    "domainStrategy": "IPIfNonMatch",
                    "rules": [{"network":"tcp,udp","balancerTag":"balance"}],
                    "balancers": [{"tag":"balance","selector":["proxy"],"strategy":{"type":"leastLoad"}}]
                  },
                  "burstObservatory": {"subjectSelector":["proxy"]}
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 255,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val root = json.parseToJsonElement(result).jsonObject
        val routing = root.getValue("routing").jsonObject
        assertEquals(
            listOf("StatsService", "RoutingService", "ObservatoryService"),
            root.getValue("api").jsonObject.getValue("services").jsonArray.map { it.jsonPrimitive.content },
        )
        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("IPIfNonMatch", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals("dns-out", rules.first().getValue("outboundTag").jsonPrimitive.content)
        assertEquals("default-dns", root.getValue("dns").jsonObject.getValue("tag").jsonPrimitive.content)
        val defaultDnsRule = rules.first {
            it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns"
        }
        assertEquals("proxy", defaultDnsRule.getValue("outboundTag").jsonPrimitive.content)
        assertEquals("balance", rules.last().getValue("balancerTag").jsonPrimitive.content)
        assertEquals("balance", routing.getValue("balancers").jsonArray.single().jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals(
            listOf("proxy", "direct", "block", "dns-out", "proxy-2"),
            root.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content },
        )
        assertEquals(
            "proxy",
            root.getValue("burstObservatory").jsonObject
                .getValue("subjectSelector").jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun `inject fails when raw config has no proxy-capable outbound`() {
        val failure = runCatching {
            injector.inject(
                rawJson = """
                    {
                      "outbounds": [
                        {"tag":"direct","protocol":"freedom"},
                        {"tag":"dns-out","protocol":"dns"},
                        {"tag":"block","protocol":"blackhole"}
                      ]
                    }
                """.trimIndent(),
                tunName = "xray0",
                fwmark = 1,
                dnsServers = "",
                domesticDnsServers = "",
                logLevel = XrayLogLevel.Error,
                defaultOutbound = XrayOutbound.Proxy,
                bypassLan = false,
                routingRules = emptyList(),
                appProxyRoutes = emptyList(),
                physicalInterface = null,
            )
        }

        assertTrue(failure.isFailure)
        assertEquals("Raw JSON config has no proxy outbound", failure.exceptionOrNull()?.message)
    }

    private fun server(name: String) = ServerConfig(
        protocol = Protocol.VLESS,
        name = name,
        address = "203.0.113.8",
        port = 443,
        password = "uuid",
        transport = ServerConfig.Transport(type = "tcp"),
        security = ServerConfig.Security(type = "none"),
    )
}
