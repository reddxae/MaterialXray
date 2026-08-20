package com.material.xray.ui.configviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSyntaxTokenizerTest {

    @Test
    fun `classifies a string as a key only when a colon follows`() {
        val tokens = tokenizeJsonLines("""  "tag": "proxy"""").single()

        assertEquals(
            listOf(
                JsonToken("  ", JsonTokenKind.Plain),
                JsonToken(""""tag"""", JsonTokenKind.Key),
                JsonToken(":", JsonTokenKind.Punctuation),
                JsonToken(" ", JsonTokenKind.Plain),
                JsonToken(""""proxy"""", JsonTokenKind.StringValue),
            ),
            tokens,
        )
    }

    @Test
    fun `a colon inside a string value does not promote it to a key`() {
        val tokens = tokenizeJsonLines("""  "address": "2001:db8::1",""").single()
        val strings = tokens.filter { it.kind == JsonTokenKind.StringValue || it.kind == JsonTokenKind.Key }

        assertEquals(
            listOf(
                JsonToken(""""address"""", JsonTokenKind.Key),
                JsonToken(""""2001:db8::1"""", JsonTokenKind.StringValue),
            ),
            strings,
        )
    }

    @Test
    fun `an escaped quote does not terminate a string`() {
        val line = """  "path": "/a\"b:c""""
        val tokens = tokenizeJsonLines(line).single()

        assertEquals(
            JsonToken(""""/a\"b:c"""", JsonTokenKind.StringValue),
            tokens.last(),
        )
    }

    @Test
    fun `recognises numbers and literals`() {
        val tokens = tokenizeJsonLines("""  "port": 443, "tls": true, "sni": null, "x": -1.5e3""").single()
        val values = tokens
            .filter { it.kind == JsonTokenKind.Number || it.kind == JsonTokenKind.Literal }
            .map { it.text }

        assertEquals(listOf("443", "true", "null", "-1.5e3"), values)
    }

    @Test
    fun `a literal embedded in a longer word is not split out`() {
        val tokens = tokenizeJsonLines("  nullable").single()

        assertTrue(tokens.none { it.kind == JsonTokenKind.Literal })
        assertEquals("  nullable", tokens.joinToString("") { it.text })
    }

    @Test
    fun `tokens reproduce the input exactly`() {
        val json = """
            {
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": { "vnext": [ { "port": 443, "users": [ { "id": "abc-123" } ] } ] },
                  "streamSettings": { "security": "reality", "realitySettings": { "show": false } }
                }
              ]
            }
        """.trimIndent()

        val roundTripped = tokenizeJsonLines(json).joinToString("\n") { line ->
            line.joinToString("") { it.text }
        }

        assertEquals(json, roundTripped)
    }

    @Test
    fun `an unterminated string consumes the rest of the line`() {
        val tokens = tokenizeJsonLines("""  "broken""").single()

        assertEquals(JsonToken(""""broken""", JsonTokenKind.StringValue), tokens.last())
    }
}
