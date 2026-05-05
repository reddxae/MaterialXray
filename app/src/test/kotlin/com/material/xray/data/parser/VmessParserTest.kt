package com.material.xray.data.parser

import com.material.xray.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class VmessParserTest {

    @Test
    fun `parse VMess base64 json link`() {
        val uri = vmess(
            """{"ps":"Tokyo","add":"1.2.3.4","port":"443","id":"uuid","aid":"0","net":"ws","host":"example.com","path":"/ws","tls":"tls","sni":"sni.example","fp":"chrome","alpn":"h2,http/1.1"}"""
        )

        val config = VmessParser.parse(uri)!!

        assertEquals(Protocol.VMESS, config.protocol)
        assertEquals("Tokyo", config.name)
        assertEquals("1.2.3.4", config.address)
        assertEquals(443, config.port)
        assertEquals("uuid", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/ws", config.transport.path)
        assertEquals("example.com", config.transport.host)
        assertEquals("tls", config.security.type)
        assertEquals("sni.example", config.security.sni)
        assertEquals("chrome", config.security.fingerprint)
        assertEquals(listOf("h2", "http/1.1"), config.security.alpn)
        assertEquals("0", config.extra["alterId"])
        assertEquals(uri, config.rawUri)
    }

    @Test
    fun `parse VMess link uses tcp and none defaults`() {
        val config = VmessParser.parse(vmess("""{"add":"1.2.3.4","port":"80","id":"uuid"}"""))!!

        assertEquals("tcp", config.transport.type)
        assertEquals("none", config.security.type)
        assertEquals("", config.name)
        assertEquals(emptyMap<String, String>(), config.extra)
    }

    @Test
    fun `parse returns null for invalid vmess payloads`() {
        assertNull(VmessParser.parse("vmess://not-base64"))
        assertNull(VmessParser.parse(vmess("""{"add":"","port":"443","id":"uuid"}""")))
        assertNull(VmessParser.parse(vmess("""{"add":"1.2.3.4","port":"bad","id":"uuid"}""")))
    }

    private fun vmess(json: String): String =
        "vmess://${Base64.getEncoder().encodeToString(json.toByteArray())}"
}
