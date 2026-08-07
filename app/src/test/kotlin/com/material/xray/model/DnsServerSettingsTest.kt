package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsServerSettingsTest {

    @Test
    fun `enabling IPv6 appends matching resolvers`() {
        assertEquals(
            "1.0.0.1,8.8.8.8,9.9.9.9,77.88.8.8," +
                "2606:4700:4700::1001,2001:4860:4860::8888,2620:fe::fe,2a02:6b8::feed:0ff",
            normalizeDnsServersForIpv6("1.0.0.1, 8.8.8.8, 9.9.9.9, 77.88.8.8", allowIpv6 = true),
        )
    }

    @Test
    fun `enabling IPv6 appends Cloudflare when no resolver has a mapping`() {
        assertEquals(
            "192.0.2.53,198.51.100.53,2606:4700:4700::1111",
            normalizeDnsServersForIpv6("192.0.2.53,198.51.100.53", allowIpv6 = true),
        )
    }

    @Test
    fun `enabling IPv6 preserves an explicit IPv6 resolver list`() {
        assertEquals(
            "1.1.1.1,2606:4700:4700::1001,2a02:6b8::feed:0ff",
            normalizeDnsServersForIpv6(
                "1.1.1.1, 2606:4700:4700::1001, 2a02:6b8::feed:0ff",
                allowIpv6 = true,
            ),
        )
    }

    @Test
    fun `disabling IPv6 removes bare bracketed and scoped IPv6 resolvers`() {
        assertEquals(
            "1.1.1.1",
            normalizeDnsServersForIpv6(
                "1.1.1.1,2606:4700:4700::1001,[2a02:6b8::feed:0ff]:53,[fe80::1%wlan0]",
                allowIpv6 = false,
            ),
        )
    }
}
