package com.material.xray.data.parser

import com.material.xray.model.Protocol
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
        val uri = "ss://${methodPassword}@1.2.3.4:8388#SS%20Server"
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
    fun `parse unknown scheme returns null`() {
        assertNull(parser.parse("http://example.com"))
    }

    @Test
    fun `parse malformed URI returns null`() {
        assertNull(parser.parse("vless://not-valid"))
    }
}
