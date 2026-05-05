package com.material.xray.data.parser

import com.material.xray.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ShadowsocksParserTest {

    @Test
    fun `parse SIP002 link with url-safe encoded user info`() {
        val methodPassword = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:testpassword".toByteArray())
        val uri = "ss://${methodPassword}@1.2.3.4:8388#SS%20Server"

        val config = ShadowsocksParser.parse(uri)!!

        assertEquals(Protocol.SHADOWSOCKS, config.protocol)
        assertEquals("SS Server", config.name)
        assertEquals("1.2.3.4", config.address)
        assertEquals(8388, config.port)
        assertEquals("testpassword", config.password)
        assertEquals("aes-256-gcm", config.extra["method"])
        assertEquals(uri, config.rawUri)
    }

    @Test
    fun `parse SIP002 link falls back to standard base64 user info`() {
        val methodPassword = Base64.getEncoder()
            .encodeToString("chacha20-ietf-poly1305:p/a+s/s".toByteArray())
        val uri = "ss://${methodPassword}@example.com:443"

        val config = ShadowsocksParser.parse(uri)!!

        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("p/a+s/s", config.password)
        assertEquals("chacha20-ietf-poly1305", config.extra["method"])
    }

    @Test
    fun `parse legacy base64 encoded full link`() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:legacy-pass@legacy.example:9000".toByteArray())
        val uri = "ss://$encoded#Legacy%20Server"

        val config = ShadowsocksParser.parse(uri)!!

        assertEquals("Legacy Server", config.name)
        assertEquals("legacy.example", config.address)
        assertEquals(9000, config.port)
        assertEquals("legacy-pass", config.password)
        assertEquals("aes-128-gcm", config.extra["method"])
    }

    @Test
    fun `parse returns null for malformed links`() {
        assertNull(ShadowsocksParser.parse("ss://not-base64@example.com:8388"))
        assertNull(ShadowsocksParser.parse("ss://YWVzLTI1Ni1nY206cGFzcw@example.com"))
        assertNull(ShadowsocksParser.parse("ss://YWVzLTI1Ni1nY20tcGFzcw"))
    }
}
