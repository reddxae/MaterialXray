package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressResolverTest {

    @Test
    fun `raw endpoint extraction includes proxy addresses and endpoints only`() {
        val hosts = rawProxyEndpointHosts(
            """
                {
                  "outbounds": [
                    {
                      "protocol": "vless",
                      "settings": {"vnext": [{"address": "one.example", "port": 443}]},
                      "streamSettings": {"realitySettings": {"serverName": "cover.example"}}
                    },
                    {
                      "protocol": "wireguard",
                      "settings": {
                        "address": ["2001:db8::2/128"],
                        "peers": [{"endpoint": "two.example:2408"}]
                      }
                    },
                    {"protocol": "vless", "settings": {"address": "Internal"}},
                    {"protocol": "vless", "settings": {"address": "b\u00fccher.example."}},
                    {"protocol": "trojan", "settings": {"servers": [{"address": "192.0.2.8"}]}},
                    {"protocol": "freedom", "settings": {"redirect": "ignored.example", "address": "ignored.example"}},
                    {"protocol": "loopback", "settings": {"address": "ignored-loopback.example"}}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("one.example", "two.example", "internal", "xn--bcher-kva.example"), hosts)
    }

    @Test
    fun `raw config resolution stores bootstrap hosts and filters IPv6`() = runTest {
        val lookups = mapOf(
            "one.example" to listOf("192.0.2.1", "2001:db8::1"),
            "two.example" to listOf("192.0.2.2"),
        )
        val resolver = ServerAddressResolver(hostLookup = { host -> lookups.getValue(host) })

        val result = resolver.resolve(rawServer("one.example", "two.example"), allowIpv6 = false)

        assertTrue(result.attempted)
        assertEquals("192.0.2.1", result.selectedAddress)
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), result.candidates)
        assertEquals(
            mapOf(
                "one.example" to listOf("192.0.2.1"),
                "two.example" to listOf("192.0.2.2"),
            ),
            result.server.bootstrapDnsHosts,
        )
        assertTrue(result.unresolvedHosts.isEmpty())
    }

    @Test
    fun `raw config resolution fails closed when any endpoint is unresolved`() = runTest {
        val resolver = ServerAddressResolver(hostLookup = { host ->
            if (host == "one.example") listOf("192.0.2.1") else emptyList()
        })

        val result = resolver.resolve(rawServer("one.example", "missing.example"))

        assertTrue(result.attempted)
        assertNull(result.selectedAddress)
        assertEquals(listOf("missing.example"), result.unresolvedHosts)
        assertTrue(result.server.bootstrapDnsHosts.isEmpty())
    }

    @Test
    fun `numeric IPv4 raw endpoints do not require bootstrap resolution`() = runTest {
        var lookupCalled = false
        val resolver = ServerAddressResolver(hostLookup = {
            lookupCalled = true
            emptyList()
        })

        val result = resolver.resolve(rawServer("192.0.2.1"))

        assertFalse(result.attempted)
        assertFalse(lookupCalled)
        assertNull(result.selectedAddress)
    }

    @Test
    fun `numeric IPv6 raw endpoint fails when IPv6 is disabled`() = runTest {
        val resolver = ServerAddressResolver(hostLookup = { emptyList() })

        val result = resolver.resolve(rawServer("2001:db8::1"), allowIpv6 = false)

        assertTrue(result.attempted)
        assertNull(result.selectedAddress)
        assertEquals(listOf("2001:db8::1"), result.unresolvedHosts)
    }

    @Test
    fun `numeric IPv6 raw endpoint needs no bootstrap when IPv6 is enabled`() = runTest {
        val resolver = ServerAddressResolver(hostLookup = { emptyList() })

        val result = resolver.resolve(rawServer("2001:db8::1"), allowIpv6 = true)

        assertFalse(result.attempted)
        assertNull(result.selectedAddress)
    }

    @Test
    fun `raw endpoint resolution runs all lookups concurrently`() = runTest {
        val addresses = Array(20) { index -> "endpoint-$index.example" }
        val startedLookups = MutableStateFlow(0)
        // Each lookup suspends until every other lookup has started, so resolution only
        // completes when all hosts are resolved concurrently.
        val resolver = ServerAddressResolver(hostLookup = {
            startedLookups.update { it + 1 }
            startedLookups.first { it == addresses.size }
            listOf("192.0.2.1")
        })

        val result = resolver.resolve(rawServer(*addresses))

        assertEquals("192.0.2.1", result.selectedAddress)
        assertEquals(addresses.size, startedLookups.value)
    }

    @Test
    fun `framework DNS failure falls back to the system lookup`() = runTest {
        val result = dnsLookupWithFallback(
            primaryLookup = { throw IllegalArgumentException("NETID_UNSET") },
            fallbackLookup = { listOf("192.0.2.1") },
        )

        assertEquals(listOf("192.0.2.1"), result)
    }

    private fun rawServer(vararg addresses: String): ServerConfig = ServerConfig(
        protocol = Protocol.RAW,
        name = "Raw",
        address = addresses.first(),
        port = 443,
        password = "",
        rawConfigJson = """
            {
              "outbounds": [
                ${addresses.joinToString(",") { address ->
            """{"protocol":"vless","settings":{"vnext":[{"address":"$address","port":443}]}}"""
        }}
              ]
            }
        """.trimIndent(),
    )
}
