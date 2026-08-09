package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_DOWN
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_INSECURE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_PIN_SHA256
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Hysteria2ParserTest {

    @Test
    fun `parse Hysteria2 link with obfs tls and port hopping`() {
        val uri = "hy2://user%3Apass@example.com:123,5000-6000/" +
            "?insecure=1&obfs=salamander&obfs-password=obfsSecret&sni=real.example.com" +
            "&pinSHA256=deadbeef&upmbps=100&downmbps=200&mportHopInt=15#HY2%20Server"

        val config = Hysteria2Parser.parse(uri)!!

        assertEquals(Protocol.HYSTERIA2, config.protocol)
        assertEquals("HY2 Server", config.name)
        assertEquals("example.com", config.address)
        assertEquals(123, config.port)
        assertEquals("user:pass", config.password)
        assertEquals("hysteria", config.transport.type)
        assertEquals("tls", config.security.type)
        assertEquals("real.example.com", config.security.sni)
        assertEquals(listOf("h3"), config.security.alpn)
        assertEquals("1", config.extra[SERVER_EXTRA_HYSTERIA_INSECURE])
        assertEquals("deadbeef", config.extra[SERVER_EXTRA_HYSTERIA_PIN_SHA256])
        assertEquals("salamander", config.extra[SERVER_EXTRA_HYSTERIA_OBFS])
        assertEquals("obfsSecret", config.extra[SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD])
        assertEquals("100 mbps", config.extra[SERVER_EXTRA_HYSTERIA_UP])
        assertEquals("200 mbps", config.extra[SERVER_EXTRA_HYSTERIA_DOWN])
        assertEquals("123,5000-6000", config.extra[SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS])
        assertEquals("15", config.extra[SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL])
    }

    @Test
    fun `parse Hysteria2 link defaults to port 443`() {
        val config = Hysteria2Parser.parse("hysteria2://secret@example.com?sni=real.example.com")!!

        assertEquals(443, config.port)
        assertEquals("secret", config.password)
        assertEquals("example.com", config.address)
    }

    @Test
    fun `parse Hysteria2 link normalizes bare bandwidth numbers to mbps`() {
        val config = Hysteria2Parser.parse("hysteria2://secret@example.com?up=100&down=200")!!

        assertEquals("100 mbps", config.extra[SERVER_EXTRA_HYSTERIA_UP])
        assertEquals("200 mbps", config.extra[SERVER_EXTRA_HYSTERIA_DOWN])
    }

    @Test
    fun `parse accepts bracketed ipv6 endpoint with port`() {
        val config = Hysteria2Parser.parse("hy2://secret@[2001:db8::1]:8443")!!

        assertEquals("2001:db8::1", config.address)
        assertEquals(8443, config.port)
    }

    @Test
    fun `parse treats unbracketed ipv6 literal as host without port`() {
        val config = Hysteria2Parser.parse("hy2://secret@2001:db8::1:8443")!!

        assertEquals("2001:db8::1:8443", config.address)
        assertEquals(443, config.port)
    }

    @Test
    fun `parse rejects unbracketed multi-colon values that are not ipv6 literals`() {
        assertNull(Hysteria2Parser.parse("hy2://secret@host:80:90"))
        assertNull(Hysteria2Parser.parse("hy2://secret@a:b:c"))
    }

    @Test
    fun `parse returns null for out of range ports`() {
        assertNull(Hysteria2Parser.parse("hy2://secret@example.com:70000"))
        assertNull(Hysteria2Parser.parse("hy2://secret@example.com:0"))
    }

    @Test
    fun `parse returns null for invalid Hysteria2 links`() {
        assertNull(Hysteria2Parser.parse("hysteria2://example.com:443"))
        assertNull(Hysteria2Parser.parse("hysteria2://secret@example.com:bad"))
        assertNull(Hysteria2Parser.parse("hysteria://secret@example.com:443"))
    }
}
