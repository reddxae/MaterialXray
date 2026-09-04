package com.material.xray.core.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hand-edited config is captured on one connect and replayed on later ones, so the identifiers
 * the app allocates per connect have to be rewritten before the core sees it.
 */
class ActiveConfigRuntimeIdentityTest {

    private val generator = ConfigGenerator()

    @Test
    fun `the stale api endpoint is replaced by the current one`() {
        val edited = """
            {
              "api": { "tag": "api", "listen": "@stale-socket-from-a-previous-connect", "services": ["StatsService"] },
              "outbounds": [{ "tag": "proxy", "protocol": "vless" }]
            }
        """.trimIndent()

        val patched = generator.applyRuntimeIdentity(
            configJson = edited,
            tunName = "xray0",
            xrayApiEndpoint = XrayApiEndpoint.UnixSocket("fresh-socket"),
        )

        val listen = patched.parse()["api"]!!.jsonObject["listen"]!!.jsonPrimitive.content
        assertEquals("@fresh-socket", listen)
    }

    @Test
    fun `a loopback api port is replaced so the firewall rule guards the right port`() {
        val edited = """
            {
              "api": { "tag": "api", "listen": "127.0.0.1:10085" },
              "outbounds": []
            }
        """.trimIndent()

        val patched = generator.applyRuntimeIdentity(
            configJson = edited,
            tunName = "xray0",
            xrayApiEndpoint = XrayApiEndpoint.LoopbackTcp(45678),
        )

        val listen = patched.parse()["api"]!!.jsonObject["listen"]!!.jsonPrimitive.content
        assertTrue("expected the fresh port, got $listen", listen.endsWith(":45678"))
    }

    @Test
    fun `an api block the user deleted is put back`() {
        val patched = generator.applyRuntimeIdentity(
            configJson = """{ "outbounds": [] }""",
            tunName = "xray0",
            xrayApiEndpoint = XrayApiEndpoint.UnixSocket("fresh-socket"),
        )

        assertEquals("@fresh-socket", patched.parse()["api"]!!.jsonObject["listen"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an edited config exposes its existing observatory on the current api`() {
        val patched = generator.applyRuntimeIdentity(
            configJson = """{ "observatory": { "subjectSelector": ["proxy"] }, "outbounds": [] }""",
            tunName = "xray0",
        ).parse()

        assertEquals(
            listOf("StatsService", "RoutingService", "ObservatoryService"),
            patched.getValue("api").jsonObject.getValue("services").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `inbounds are rebuilt for the tun this connect actually created`() {
        val edited = """
            {
              "inbounds": [{ "tag": "tun-in", "protocol": "tun", "settings": { "name": "wlan7" } }],
              "outbounds": []
            }
        """.trimIndent()

        val patched = requireNotNull(generator.applyRuntimeIdentity(configJson = edited, tunName = "wlan3"))

        val inbounds = patched.parse()["inbounds"]!!.jsonArray
        assertEquals(1, inbounds.size)
        assertTrue("expected the current tun name in $inbounds", patched.contains("wlan3"))
        assertTrue("the stale tun name should be gone", !patched.contains("wlan7"))
    }

    @Test
    fun `explicit inbounds win, so tproxy ports match the installed rules`() {
        val patched = generator.applyRuntimeIdentity(
            configJson = """{ "inbounds": [], "outbounds": [] }""",
            tunName = "xray0",
            inbounds = listOf(XrayInbound.Tproxy(port = 12345, tag = "tproxy-in", outboundMark = 255, allowIpv6 = false)),
        )

        val inbounds = patched.parse()["inbounds"]!!.jsonArray
        assertEquals(1, inbounds.size)
        assertEquals("tproxy-in", inbounds[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals(12345, inbounds[0].jsonObject["port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `everything the user edited is left alone`() {
        val edited = """
            {
              "log": { "loglevel": "debug" },
              "dns": { "servers": ["9.9.9.9"] },
              "routing": { "rules": [{ "type": "field", "domain": ["example.com"], "outboundTag": "block" }] },
              "outbounds": [{ "tag": "proxy", "protocol": "vless", "settings": { "hand": "edited" } }],
              "inbounds": [],
              "somethingCustom": 42
            }
        """.trimIndent()

        val patched = generator.applyRuntimeIdentity(configJson = edited, tunName = "xray0").parse()

        assertEquals("debug", patched["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive.content)
        assertEquals("9.9.9.9", patched["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(1, patched["routing"]!!.jsonObject["rules"]!!.jsonArray.size)
        assertEquals(42, patched["somethingCustom"]!!.jsonPrimitive.content.toInt())
        val proxy = patched["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("edited", proxy["settings"]!!.jsonObject["hand"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unusable document returns null so the caller can fall back to generation`() {
        assertNull(generator.applyRuntimeIdentity(configJson = "not json at all", tunName = "xray0"))
        assertNull(generator.applyRuntimeIdentity(configJson = "[1, 2, 3]", tunName = "xray0"))
        assertNull(generator.applyRuntimeIdentity(configJson = "", tunName = "xray0"))
    }

    private fun String?.parse(): JsonObject = Json.parseToJsonElement(requireNotNull(this)).jsonObject
}
