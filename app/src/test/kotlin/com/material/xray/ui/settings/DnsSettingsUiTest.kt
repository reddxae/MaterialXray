package com.material.xray.ui.settings

import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.DnsPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSettingsUiTest {

    @Test
    fun `two hand-written IPv4-only lists are flagged`() {
        assertTrue(hasIpv4OnlyDnsServers("192.0.2.53,198.51.100.53", "192.0.2.54"))
        assertTrue(hasIpv4OnlyDnsServers("https://dns.google/dns-query", "192.0.2.54"))
    }

    @Test
    fun `an IPv6 resolver in either list clears the flag`() {
        assertFalse(hasIpv4OnlyDnsServers("192.0.2.53,2001:db8::53", "192.0.2.54"))
        assertFalse(hasIpv4OnlyDnsServers("192.0.2.53", "77.88.8.8,2a02:6b8::feed:0ff"))
    }

    @Test
    fun `an empty list is an unknown rather than an absence`() {
        // Nothing configured hands that lookup to the OS resolver, which a dual-stack network may
        // well have given an IPv6 address, so the note cannot claim there is none.
        assertFalse(hasIpv4OnlyDnsServers("", ""))
        assertFalse(hasIpv4OnlyDnsServers("", "192.0.2.53"))
        assertFalse(hasIpv4OnlyDnsServers("192.0.2.53", ""))
    }

    @Test
    fun `no preset is ever flagged, encrypted or not`() {
        val ipv4Only = "192.0.2.53"
        DnsPreset.entries.filter { it.supportsEncryption }.forEach { preset ->
            assertFalse(preset.name, hasIpv4OnlyDnsServers(preset.servers(encrypted = false), ipv4Only))
            if (preset.encryptsIpv6) {
                assertFalse(preset.name, hasIpv4OnlyDnsServers(preset.servers(encrypted = true), ipv4Only))
            }
        }
    }

    @Test
    fun `encrypting a provider that has no IPv6 certificate is flagged`() {
        // Encrypting AdGuard or Yandex drops their IPv6 addresses, so the note is what explains
        // where they went.
        assertTrue(hasIpv4OnlyDnsServers(DnsPreset.AdGuard.servers(encrypted = true), "192.0.2.53"))
        assertTrue(hasIpv4OnlyDnsServers(DnsPreset.Yandex.servers(encrypted = true), "192.0.2.53"))
    }

    @Test
    fun `the shipped defaults are not flagged`() {
        assertFalse(
            hasIpv4OnlyDnsServers(
                SettingsRepository.DEFAULT_DNS_SERVERS,
                SettingsRepository.DEFAULT_DOMESTIC_DNS_SERVERS,
            ),
        )
    }
}
