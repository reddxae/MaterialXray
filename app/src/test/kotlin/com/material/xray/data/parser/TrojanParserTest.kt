package com.material.xray.data.parser

import com.material.xray.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrojanParserTest {

    @Test
    fun `parse trojan link with transport and security params`() {
        val uri = "trojan://pa%3Ass@example.com:443?security=tls&sni=sni.example&fp=chrome" +
            "&alpn=h2,http/1.1&type=ws&path=%2Ftrojan&host=edge.example&serviceName=grpc" +
            "#My%20Trojan"

        val config = TrojanParser.parse(uri)!!

        assertEquals(Protocol.TROJAN, config.protocol)
        assertEquals("My Trojan", config.name)
        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("pa:ss", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/trojan", config.transport.path)
        assertEquals("edge.example", config.transport.host)
        assertEquals("grpc", config.transport.serviceName)
        assertEquals("tls", config.security.type)
        assertEquals("sni.example", config.security.sni)
        assertEquals("chrome", config.security.fingerprint)
        assertEquals(listOf("h2", "http/1.1"), config.security.alpn)
        assertEquals(uri, config.rawUri)
    }

    @Test
    fun `parse trojan link uses tcp and tls defaults`() {
        val config = TrojanParser.parse("trojan://secret@example.com:443")!!

        assertEquals("tcp", config.transport.type)
        assertEquals("tls", config.security.type)
        assertEquals("", config.name)
    }

    @Test
    fun `parse returns null for missing required parts`() {
        assertNull(TrojanParser.parse("trojan://example.com:443"))
        assertNull(TrojanParser.parse("trojan://secret@example.com"))
        assertNull(TrojanParser.parse("not a uri"))
    }
}
