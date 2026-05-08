package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XrayConfigOutboundsTest {

    @Test
    fun `buildCoreOutbounds puts configured default first and app routes before fallback outbounds`() {
        val outbounds = buildCoreOutbounds(
            defaultOutbound = XrayOutbound.Direct,
            proxyOutbound = tagged("proxy"),
            directOutbound = tagged("direct"),
            dnsOutbound = tagged("dns-out"),
            blockOutbound = tagged("block"),
            appProxyOutbounds = listOf(tagged("app-proxy-7")),
        )

        assertEquals(
            listOf("direct", "app-proxy-7", "proxy", "block", "dns-out"),
            outbounds.map { it["tag"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildSockopt includes fwmark domain strategy and optional physical interface`() {
        val withInterface = buildSockopt(fwmark = 255, physicalInterface = "wlan0")
        assertEquals("255", withInterface.getValue("mark").jsonPrimitive.content)
        assertEquals("UseIPv4", withInterface.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals("wlan0", withInterface.getValue("interface").jsonPrimitive.content)

        val withoutInterface = buildSockopt(fwmark = 7, physicalInterface = "")
        assertEquals("7", withoutInterface.getValue("mark").jsonPrimitive.content)
        assertFalse("interface" in withoutInterface)

        val withIpv6 = buildSockopt(fwmark = 0, physicalInterface = null, allowIpv6 = true)
        assertEquals("UseIP", withIpv6.getValue("domainStrategy").jsonPrimitive.content)
    }

    @Test
    fun `buildProxyOutbound wraps normal server with stream sockopt`() {
        val outbound = buildProxyOutbound(
            server = server("Normal"),
            fwmark = 100,
            physicalInterface = "rmnet0",
            tag = "proxy",
        )

        assertEquals("proxy", outbound.getValue("tag").jsonPrimitive.content)
        assertEquals("vless", outbound.getValue("protocol").jsonPrimitive.content)
        val stream = outbound.getValue("streamSettings").jsonObject
        assertEquals("tcp", stream.getValue("network").jsonPrimitive.content)
        assertEquals("rmnet0", stream.getValue("sockopt").jsonObject.getValue("interface").jsonPrimitive.content)
    }

    @Test
    fun `buildProxyOutbound retags raw proxy candidate and updates sockopt`() {
        val outbound = buildProxyOutbound(
            server = server("Raw").copy(
                rawConfigJson = """
                    {
                      "outbounds": [
                        {"protocol":"freedom","tag":"direct"},
                        {"protocol":"vless","settings":{}}
                      ]
                    }
                """.trimIndent()
            ),
            fwmark = 9,
            physicalInterface = "wlan1",
            tag = "app-proxy-7",
        )

        assertEquals("app-proxy-7", outbound.getValue("tag").jsonPrimitive.content)
        assertEquals("vless", outbound.getValue("protocol").jsonPrimitive.content)
        val sockopt = outbound.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
        assertEquals("9", sockopt.getValue("mark").jsonPrimitive.content)
        assertEquals("wlan1", sockopt.getValue("interface").jsonPrimitive.content)
    }

    private fun tagged(tag: String) = buildJsonObject { put("tag", tag) }

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
