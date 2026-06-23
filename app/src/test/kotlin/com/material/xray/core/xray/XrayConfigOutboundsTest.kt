package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_CONGESTION
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_DOWN
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_INSECURE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_PIN_SHA256
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UP
import com.material.xray.model.SERVER_EXTRA_MLDSA65_VERIFY
import com.material.xray.model.SERVER_EXTRA_XHTTP_EXTRA
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
    fun `buildProxyOutbound includes VLESS Reality pqv and XHTTP extra settings`() {
        val outbound = buildProxyOutbound(
            server = server("XHTTP").copy(
                transport = ServerConfig.Transport(
                    type = "xhttp",
                    path = "/playlist-256000.m3u8",
                    mode = "auto",
                ),
                security = ServerConfig.Security(
                    type = "reality",
                    sni = "aud-stream.example",
                    fingerprint = "firefox",
                    publicKey = "publicKey",
                    shortId = "b73f4612c9a9",
                ),
                extra = mapOf(
                    SERVER_EXTRA_XHTTP_EXTRA to """{"xPaddingBytes":"31-68","scMaxBufferedPosts":30}""",
                    SERVER_EXTRA_MLDSA65_VERIFY to "verifyKey",
                ),
            ),
            fwmark = 100,
            physicalInterface = null,
            tag = "proxy",
        )

        val stream = outbound.getValue("streamSettings").jsonObject
        val reality = stream.getValue("realitySettings").jsonObject
        assertEquals("verifyKey", reality.getValue("mldsa65Verify").jsonPrimitive.content)

        val xhttp = stream.getValue("xhttpSettings").jsonObject
        assertEquals("/playlist-256000.m3u8", xhttp.getValue("path").jsonPrimitive.content)
        assertEquals("auto", xhttp.getValue("mode").jsonPrimitive.content)
        val extra = xhttp.getValue("extra").jsonObject
        assertEquals("31-68", extra.getValue("xPaddingBytes").jsonPrimitive.content)
        assertEquals(30, extra.getValue("scMaxBufferedPosts").jsonPrimitive.int)
    }

    @Test
    fun `buildProxyOutbound falls back to raw VLESS uri for newer share params`() {
        val outbound = buildProxyOutbound(
            server = server("Stored").copy(
                transport = ServerConfig.Transport(type = "xhttp", mode = "auto"),
                security = ServerConfig.Security(type = "reality", publicKey = "publicKey"),
                rawUri = "vless://uuid@example.com:443?" +
                    "extra=%7B%22token%22%3A%22a+b%22%2C%22scMaxBufferedPosts%22%3A30%7D&pqv=verify+Key",
            ),
            fwmark = 100,
            physicalInterface = null,
            tag = "proxy",
        )

        val stream = outbound.getValue("streamSettings").jsonObject
        assertEquals(
            "verify+Key",
            stream.getValue("realitySettings").jsonObject.getValue("mldsa65Verify").jsonPrimitive.content,
        )
        assertEquals(
            "a+b",
            stream.getValue("xhttpSettings").jsonObject
                .getValue("extra").jsonObject
                .getValue("token").jsonPrimitive.content,
        )
        assertEquals(
            30,
            stream.getValue("xhttpSettings").jsonObject
                .getValue("extra").jsonObject
                .getValue("scMaxBufferedPosts").jsonPrimitive.int,
        )
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
                """.trimIndent(),
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

    @Test
    fun `buildProxyOutbound applies domain strategy override to raw proxy sockopt`() {
        val outbound = buildProxyOutbound(
            server = server("Raw").copy(
                rawConfigJson = """
                    {
                      "outbounds": [
                        {"protocol":"vless","tag":"proxy","settings":{},"streamSettings":{}}
                      ]
                    }
                """.trimIndent(),
            ),
            fwmark = 0,
            physicalInterface = null,
            tag = "proxy",
            domainStrategyOverride = "AsIs",
        )

        val sockopt = outbound.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
        assertEquals("AsIs", sockopt.getValue("domainStrategy").jsonPrimitive.content)
    }

    @Test
    fun `buildProxyOutbound applies domain strategy override to generated proxy sockopt`() {
        val outbound = buildProxyOutbound(
            server = server("Generated"),
            fwmark = 0,
            physicalInterface = null,
            tag = "proxy",
            domainStrategyOverride = "AsIs",
        )

        val sockopt = outbound.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
        assertEquals("AsIs", sockopt.getValue("domainStrategy").jsonPrimitive.content)
    }

    @Test
    fun `buildProxyOutbound creates Hysteria2 outbound settings and transport`() {
        val outbound = buildProxyOutbound(
            server = ServerConfig(
                protocol = Protocol.HYSTERIA2,
                name = "HY2",
                address = "hy.example.com",
                port = 443,
                password = "authSecret",
                transport = ServerConfig.Transport(type = "hysteria"),
                security = ServerConfig.Security(type = "tls", sni = "real.example.com"),
                extra = mapOf(
                    SERVER_EXTRA_HYSTERIA_INSECURE to "1",
                    SERVER_EXTRA_HYSTERIA_PIN_SHA256 to "deadbeef",
                    SERVER_EXTRA_HYSTERIA_OBFS to "salamander",
                    SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD to "obfsSecret",
                    SERVER_EXTRA_HYSTERIA_UP to "100 mbps",
                    SERVER_EXTRA_HYSTERIA_DOWN to "200 mbps",
                    SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS to "20000-30000",
                    SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL to "30",
                    SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT to "120",
                    SERVER_EXTRA_HYSTERIA_CONGESTION to "brutal",
                ),
            ),
            fwmark = 100,
            physicalInterface = null,
            tag = "proxy",
        )

        assertEquals("hysteria", outbound.getValue("protocol").jsonPrimitive.content)
        val settings = outbound.getValue("settings").jsonObject
        assertEquals(2, settings.getValue("version").jsonPrimitive.int)
        assertEquals("hy.example.com", settings.getValue("address").jsonPrimitive.content)
        assertEquals(443, settings.getValue("port").jsonPrimitive.int)

        val stream = outbound.getValue("streamSettings").jsonObject
        assertEquals("hysteria", stream.getValue("network").jsonPrimitive.content)
        assertEquals("tls", stream.getValue("security").jsonPrimitive.content)

        val tls = stream.getValue("tlsSettings").jsonObject
        assertEquals("real.example.com", tls.getValue("serverName").jsonPrimitive.content)
        assertEquals("h3", tls.getValue("alpn").jsonArray.single().jsonPrimitive.content)
        assertFalse("allowInsecure" in tls)
        assertEquals("deadbeef", tls.getValue("pinnedPeerCertSha256").jsonPrimitive.content)

        val hysteria = stream.getValue("hysteriaSettings").jsonObject
        assertEquals(2, hysteria.getValue("version").jsonPrimitive.int)
        assertEquals("authSecret", hysteria.getValue("auth").jsonPrimitive.content)
        assertEquals(120, hysteria.getValue("udpIdleTimeout").jsonPrimitive.int)

        val finalMask = stream.getValue("finalmask").jsonObject
        val udpMask = finalMask.getValue("udp").jsonArray.single().jsonObject
        assertEquals("salamander", udpMask.getValue("type").jsonPrimitive.content)
        assertEquals("obfsSecret", udpMask.getValue("settings").jsonObject.getValue("password").jsonPrimitive.content)

        val quicParams = finalMask.getValue("quicParams").jsonObject
        assertEquals("brutal", quicParams.getValue("congestion").jsonPrimitive.content)
        assertEquals("100 mbps", quicParams.getValue("brutalUp").jsonPrimitive.content)
        assertEquals("200 mbps", quicParams.getValue("brutalDown").jsonPrimitive.content)
        val udpHop = quicParams.getValue("udpHop").jsonObject
        assertEquals("20000-30000", udpHop.getValue("ports").jsonPrimitive.content)
        assertEquals(30, udpHop.getValue("interval").jsonPrimitive.int)
    }

    @Test
    fun `buildProxyOutbound maps Hysteria2 gecko obfs to salamander packet size`() {
        val outbound = buildProxyOutbound(
            server = ServerConfig(
                protocol = Protocol.HYSTERIA2,
                name = "HY2 Gecko",
                address = "hy.example.com",
                port = 443,
                password = "authSecret",
                transport = ServerConfig.Transport(type = "hysteria"),
                security = ServerConfig.Security(type = "tls"),
                extra = mapOf(
                    SERVER_EXTRA_HYSTERIA_OBFS to "gecko",
                    SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD to "obfsSecret",
                ),
            ),
            fwmark = 100,
            physicalInterface = null,
            tag = "proxy",
        )

        val udpMask = outbound.getValue("streamSettings").jsonObject
            .getValue("finalmask").jsonObject
            .getValue("udp").jsonArray
            .single().jsonObject
        assertEquals("salamander", udpMask.getValue("type").jsonPrimitive.content)
        assertEquals("512-1200", udpMask.getValue("settings").jsonObject.getValue("packetSize").jsonPrimitive.content)
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
