package com.material.xray.data.parser

import com.material.xray.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlessParserTest {

    @Test
    fun `parse VLESS link with reality xhttp and extras`() {
        val uri = "vless://uuid@example.com:443?encryption=none&flow=xtls-rprx-vision" +
            "&type=xhttp&path=%2Fapi%2Fv1&host=edge.example&mode=auto&serviceName=svc" +
            "&extra=%7B%22xPaddingBytes%22%3A%2231-68%22%7D" +
            "&security=reality&sni=sni.example&fp=chrome&pbk=publicKey&sid=shortId" +
            "&pqv=verifyKey&spx=%2Fcrawler&alpn=h2,http/1.1" +
            "#VLESS%20Server"

        val config = VlessParser.parse(uri)!!

        assertEquals(Protocol.VLESS, config.protocol)
        assertEquals("VLESS Server", config.name)
        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("uuid", config.password)
        assertEquals("xhttp", config.transport.type)
        assertEquals("/api/v1", config.transport.path)
        assertEquals("edge.example", config.transport.host)
        assertEquals("svc", config.transport.serviceName)
        assertEquals("auto", config.transport.mode)
        assertEquals("reality", config.security.type)
        assertEquals("sni.example", config.security.sni)
        assertEquals("chrome", config.security.fingerprint)
        assertEquals("publicKey", config.security.publicKey)
        assertEquals("shortId", config.security.shortId)
        assertEquals(listOf("h2", "http/1.1"), config.security.alpn)
        assertEquals("none", config.extra["encryption"])
        assertEquals("xtls-rprx-vision", config.extra["flow"])
        assertEquals("{\"xPaddingBytes\":\"31-68\"}", config.extra["xhttpExtra"])
        assertEquals("verifyKey", config.extra["mldsa65Verify"])
        assertEquals("/crawler", config.extra["spiderX"])
        assertEquals(uri, config.rawUri)
    }

    @Test
    fun `parse VLESS link uses tcp and none defaults`() {
        val config = VlessParser.parse("vless://uuid@example.com:8443")!!

        assertEquals("tcp", config.transport.type)
        assertEquals("none", config.security.type)
        assertEquals("", config.name)
        assertEquals(emptyMap<String, String>(), config.extra)
    }

    @Test
    fun `parse VLESS link preserves literal plus in uri components`() {
        val uri = "vless://uuid@example.com:443?type=xhttp&path=%2Fapi+v1" +
            "&extra=%7B%22token%22%3A%22a+b%22%7D&pqv=verify+Key&spx=%2Fcrawler+bot" +
            "#Plus+Server"

        val config = VlessParser.parse(uri)!!

        assertEquals("Plus+Server", config.name)
        assertEquals("/api+v1", config.transport.path)
        assertEquals("{\"token\":\"a+b\"}", config.extra["xhttpExtra"])
        assertEquals("verify+Key", config.extra["mldsa65Verify"])
        assertEquals("/crawler+bot", config.extra["spiderX"])
    }

    @Test
    fun `parse returns null for missing required parts`() {
        assertNull(VlessParser.parse("vless://example.com:443"))
        assertNull(VlessParser.parse("vless://uuid@example.com"))
        assertNull(VlessParser.parse("not a uri"))
    }
}
