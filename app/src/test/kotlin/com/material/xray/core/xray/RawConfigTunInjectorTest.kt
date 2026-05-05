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

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val injector = RawConfigTunInjector(json)

    @Test
    fun `inject adds tun inbounds and managed outbounds around raw proxy candidate`() {
        val result = injector.inject(
            rawJson = """
                {
                  "inbounds": [{"tag":"socks-in","protocol":"socks"}],
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
                )
            ),
            physicalInterface = "wlan0",
        )

        val root = json.parseToJsonElement(result).jsonObject
        val inbounds = root.getValue("inbounds").jsonArray
        assertEquals(listOf("tun-in", "socks-in", "app-in-7"), inbounds.map { it.jsonObject["tag"]!!.jsonPrimitive.content })
        assertEquals("xray0", inbounds.first().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("xray0a1", inbounds.last().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)

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
    fun `inject preserves existing tun inbound instead of duplicating it`() {
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
        assertEquals(1, inbounds.count { it.jsonObject["protocol"]!!.jsonPrimitive.content == "tun" })
        assertEquals("existing0", inbounds.first().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
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
