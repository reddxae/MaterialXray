package com.material.xray.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParsingTest {

    @Test
    fun `parseQuery decodes keys and values`() {
        val params = parseQuery("service%20Name=my%2Fsvc&sni=a%2Bb.example")

        assertEquals("my/svc", params["service Name"])
        assertEquals("a+b.example", params["sni"])
    }

    @Test
    fun `parseQuery preserves literal plus signs`() {
        val params = parseQuery("path=/api+v1&token=a+b")

        assertEquals("/api+v1", params["path"])
        assertEquals("a+b", params["token"])
    }

    @Test
    fun `parseQuery keeps values with malformed escapes verbatim`() {
        val params = parseQuery("name=100%&path=%2Fok")

        assertEquals("100%", params["name"])
        assertEquals("/ok", params["path"])
    }

    @Test
    fun `parseQuery skips segments without a value`() {
        val params = parseQuery("flag&key=value&")

        assertEquals(mapOf("key" to "value"), params)
    }

    @Test
    fun `parseQuery keeps everything after the first equals sign`() {
        val params = parseQuery("extra=%7B%22a%22%3D%22b%22%7D")

        assertEquals("{\"a\"=\"b\"}", params["extra"])
    }
}
