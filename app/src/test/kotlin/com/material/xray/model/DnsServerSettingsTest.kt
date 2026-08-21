package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServerSettingsTest {

    @Test
    fun `allowing IPv6 keeps the whole list as stored`() {
        assertEquals(
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
            resolveDnsServersForIpv6("1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001", allowIpv6 = true),
        )
    }

    @Test
    fun `disallowing IPv6 removes bare bracketed and scoped IPv6 resolvers`() {
        assertEquals(
            listOf("1.1.1.1"),
            resolveDnsServersForIpv6(
                "1.1.1.1,2606:4700:4700::1001,[2a02:6b8::feed:0ff]:53,[fe80::1%wlan0]",
                allowIpv6 = false,
            ),
        )
    }

    @Test
    fun `disallowing IPv6 keeps explicit endpoints and drops IPv6-only ones`() {
        assertEquals(
            listOf("https://1.1.1.1/dns-query", "https://dns.google/dns-query"),
            resolveDnsServersForIpv6(
                "https://1.1.1.1/dns-query,tcp://[2606:4700:4700::1111]:53,https://dns.google/dns-query",
                allowIpv6 = false,
            ),
        )
    }

    @Test
    fun `an IPv4-only list is untouched either way`() {
        assertEquals(listOf("192.0.2.53"), resolveDnsServersForIpv6("192.0.2.53", allowIpv6 = true))
        assertEquals(listOf("192.0.2.53"), resolveDnsServersForIpv6("192.0.2.53", allowIpv6 = false))
    }

    @Test
    fun `IPv6 resolvers are named in every form the setting can hold`() {
        assertEquals(
            listOf("2606:4700:4700::1111", "https://[2606:4700:4700::1001]/dns-query", "[2a02:6b8::feed:0ff]:53"),
            ipv6DnsServers(
                "1.1.1.1,2606:4700:4700::1111,https://[2606:4700:4700::1001]/dns-query,[2a02:6b8::feed:0ff]:53",
            ),
        )
    }

    @Test
    fun `a list with no IPv6 resolver names none`() {
        assertTrue(ipv6DnsServers("1.1.1.1,https://dns.google/dns-query").isEmpty())
    }
}
