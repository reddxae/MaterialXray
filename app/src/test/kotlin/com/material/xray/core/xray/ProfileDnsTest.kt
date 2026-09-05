package com.material.xray.core.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDnsTest {
    @Test
    fun `bootstrap preserves provider exact domain keyword regex and external host rules`() {
        val rules = listOf(
            "one.example", "ONE.EXAMPLE", "full:one.example", "full:ONE.EXAMPLE", "domain:example",
            "keyword:one", "regexp:^one\\.example$", "geosite:example", "ext:custom.dat:example",
        )
        rules.forEach { rule ->
            val dns = JsonObject(mapOf("hosts" to JsonObject(mapOf(rule to JsonPrimitive("provider-alias.example")))))
            assertEquals(rule, dns, dns.withBootstrapDnsHosts(mapOf("one.example" to listOf("192.0.2.1"))))
        }
    }

    @Test
    fun `unrelated provider rules leave room for missing endpoint addresses`() {
        val dns = Json.parseToJsonElement("""{"hosts":{"domain:other.example":"192.0.2.10"}}""").jsonObject
        val result = dns.withBootstrapDnsHosts(mapOf("one.example" to listOf("192.0.2.1")))
        val hosts = result.getValue("hosts").jsonObject
        assertEquals("192.0.2.1", hosts.getValue("one.example").jsonArray.single().jsonPrimitive.content)
        assertEquals("192.0.2.10", hosts.getValue("domain:other.example").jsonPrimitive.content)
    }
}
