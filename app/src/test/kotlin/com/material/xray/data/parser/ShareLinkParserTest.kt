package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_USERNAME
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_ADDRESS
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_MTU
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_PUBLIC_KEY
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_RESERVED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkParserTest {

    private val parser = ShareLinkParser()

    @Test
    fun `parse VLESS REALITY link`() {
        val uri = "vless://fe98768f-8ff3-4cc1-a8bd-235dd4856422@finland.bcvpn.rknotso.site:443" +
            "?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality" +
            "&sni=o-zone.ai&fp=chrome&pbk=KgCgITTjjYYKWCACqo-ELmMs3aoI3Ya4XHOUjqcIgT4" +
            "#%F0%9F%87%AB%F0%9F%87%AE%20Finland"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.VLESS, config.protocol)
        assertEquals("finland.bcvpn.rknotso.site", config.address)
        assertEquals(443, config.port)
        assertEquals("fe98768f-8ff3-4cc1-a8bd-235dd4856422", config.password)
        assertEquals("tcp", config.transport.type)
        assertEquals("reality", config.security.type)
        assertEquals("o-zone.ai", config.security.sni)
        assertEquals("chrome", config.security.fingerprint)
        assertEquals("KgCgITTjjYYKWCACqo-ELmMs3aoI3Ya4XHOUjqcIgT4", config.security.publicKey)
        assertEquals("xtls-rprx-vision", config.extra["flow"])
        assertTrue(config.name.contains("Finland"))
    }

    @Test
    fun `parse VLESS xhttp link`() {
        val uri = "vless://fe98768f-8ff3-4cc1-a8bd-235dd4856422@176.108.252.54:443" +
            "?encryption=none&type=xhttp&path=%2Fapi%2Fv1&host=invest-helper.ru&mode=auto" +
            "&security=reality&sni=invest-helper.ru&fp=chrome" +
            "&pbk=GIaeJdOncW-5-blZTWOovjP5yBNiO-JLRGpQfgOt5CI" +
            "#Test%20Server"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals("xhttp", config.transport.type)
        assertEquals("/api/v1", config.transport.path)
        assertEquals("invest-helper.ru", config.transport.host)
        assertEquals("auto", config.transport.mode)
    }

    @Test
    fun `parse VMess base64 link`() {
        val json = """{"v":"2","ps":"Tokyo","add":"1.2.3.4","port":"443","id":"abc-def","aid":"0","net":"ws","type":"none","host":"example.com","path":"/ws","tls":"tls","sni":"example.com"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val uri = "vmess://$encoded"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.VMESS, config.protocol)
        assertEquals("Tokyo", config.name)
        assertEquals("1.2.3.4", config.address)
        assertEquals(443, config.port)
        assertEquals("abc-def", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/ws", config.transport.path)
        assertEquals("example.com", config.transport.host)
        assertEquals("tls", config.security.type)
    }

    @Test
    fun `parse Trojan link`() {
        val uri = "trojan://mypassword@server.example.com:443?security=tls&sni=server.example.com&type=ws&path=%2Ftrojan#MyServer"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.TROJAN, config.protocol)
        assertEquals("server.example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("mypassword", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/trojan", config.transport.path)
        assertEquals("tls", config.security.type)
        assertEquals("MyServer", config.name)
    }

    @Test
    fun `parse Shadowsocks SIP002 link`() {
        val methodPassword = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:testpassword".toByteArray())
        val uri = "ss://$methodPassword@1.2.3.4:8388#SS%20Server"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.SHADOWSOCKS, config.protocol)
        assertEquals("1.2.3.4", config.address)
        assertEquals(8388, config.port)
        assertEquals("testpassword", config.password)
        assertEquals("aes-256-gcm", config.extra["method"])
        assertEquals("SS Server", config.name)
    }

    @Test
    fun `parse Hysteria2 link`() {
        val uri = "hysteria2://secret@example.com:443?sni=real.example.com#HY2%20Server"
        val config = parser.parse(uri)
        assertNotNull(config)
        config!!
        assertEquals(Protocol.HYSTERIA2, config.protocol)
        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("secret", config.password)
        assertEquals("hysteria", config.transport.type)
        assertEquals("tls", config.security.type)
        assertEquals("HY2 Server", config.name)
    }

    @Test
    fun `parse HTTP and SOCKS links from subscription body`() {
        val socksCredentials = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("socks-user:socks-pass".toByteArray())
        val configs = parser.parseMultiple(
            """
            https://http-user:http%3Apass@proxy.example.com:8443#HTTP%20Proxy
            socks://$socksCredentials@socks.example.com:1080#SOCKS%20Proxy
            """.trimIndent(),
        )

        assertEquals(2, configs.size)
        assertEquals(Protocol.HTTP, configs[0].protocol)
        assertEquals("http-user", configs[0].extra[SERVER_EXTRA_USERNAME])
        assertEquals("http:pass", configs[0].password)
        assertEquals("tls", configs[0].security.type)
        assertEquals(Protocol.SOCKS, configs[1].protocol)
        assertEquals("socks-user", configs[1].extra[SERVER_EXTRA_USERNAME])
        assertEquals("socks-pass", configs[1].password)
    }

    @Test
    fun `parse HTTP proxy with default port from subscription body`() {
        val config = parser.parseMultiple("http://proxy.example.com#HTTP").single()

        assertEquals(Protocol.HTTP, config.protocol)
        assertEquals(80, config.port)
    }

    @Test
    fun `parse legacy whole-payload SOCKS link from subscription body`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("alice:secret@socks.example.com:1080".toByteArray())

        val config = parser.parseMultiple("socks://$payload#Legacy%20SOCKS").single()

        assertEquals(Protocol.SOCKS, config.protocol)
        assertEquals("socks.example.com", config.address)
        assertEquals("alice", config.extra[SERVER_EXTRA_USERNAME])
        assertEquals("secret", config.password)
        assertEquals("Legacy SOCKS", config.name)
    }

    @Test
    fun `parse standard Base64 SOCKS credentials containing slash`() {
        val credentials = java.util.Base64.getEncoder().encodeToString("alice:???".toByteArray())
        assertTrue('/' in credentials)

        val config = parser.parseMultiple("socks://$credentials@socks.example.com:1080").single()

        assertEquals("alice", config.extra[SERVER_EXTRA_USERNAME])
        assertEquals("???", config.password)
    }

    @Test
    fun `parse WireGuard link from subscription body`() {
        val uri = "wireguard://private%2Bkey%3D@[2001:db8::1]:51820" +
            "?publickey=public%2Bkey%3D&address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A%3A2%2F128" +
            "&mtu=1280&reserved=1%2C2%2C3#WireGuard%20Proxy"

        val config = parser.parseMultiple(uri).single()

        assertEquals(Protocol.WIREGUARD, config.protocol)
        assertEquals("2001:db8::1", config.address)
        assertEquals(51820, config.port)
        assertEquals("private+key=", config.password)
        assertEquals("public+key=", config.extra[SERVER_EXTRA_WIREGUARD_PUBLIC_KEY])
        assertEquals("172.16.0.2/32,2606:4700:110::2/128", config.extra[SERVER_EXTRA_WIREGUARD_ADDRESS])
        assertEquals("1280", config.extra[SERVER_EXTRA_WIREGUARD_MTU])
        assertEquals("1,2,3", config.extra[SERVER_EXTRA_WIREGUARD_RESERVED])
        assertEquals("WireGuard Proxy", config.name)
    }

    @Test
    fun `parse unknown scheme returns null`() {
        assertNull(parser.parse("http://user:pass@example.com:8080"))
    }

    @Test
    fun `parse malformed URI returns null`() {
        assertNull(parser.parse("vless://not-valid"))
    }
}
