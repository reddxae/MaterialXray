package com.material.xray.model

import com.material.xray.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPresetTest {

    private val providers = DnsPreset.entries.filter { it.supportsEncryption }

    @Test
    fun `a provider offers both address families in plain form`() {
        assertEquals(
            "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001",
            DnsPreset.Cloudflare.servers(encrypted = false),
        )
    }

    @Test
    fun `a provider whose certificate covers IPv6 offers encrypted endpoints for both families`() {
        assertEquals(
            "https://9.9.9.9/dns-query,https://149.112.112.112/dns-query," +
                "https://[2620:fe::fe]/dns-query,https://[2620:fe::9]/dns-query",
            DnsPreset.Quad9.servers(encrypted = true),
        )
    }

    @Test
    fun `a provider whose certificate omits IPv6 encrypts over IPv4 only`() {
        assertEquals(
            "https://94.140.14.14/dns-query,https://94.140.15.15/dns-query",
            DnsPreset.AdGuard.servers(encrypted = true),
        )
        assertEquals(
            "https://77.88.8.8/dns-query,https://77.88.8.1/dns-query",
            DnsPreset.Yandex.servers(encrypted = true),
        )
    }

    @Test
    fun `every provider answers DNS over HTTPS on its own addresses`() {
        // Each of these was checked against the resolver's certificate. Adding a provider means
        // checking its certificate too, not copying a neighbour's flags.
        assertEquals(6, providers.size)
        assertTrue(providers.all { it.ipv4Servers.isNotEmpty() && it.ipv6Servers.isNotEmpty() })
        assertEquals(
            listOf(DnsPreset.AdGuard, DnsPreset.Yandex),
            providers.filterNot { it.encryptsIpv6 },
        )
    }

    @Test
    fun `the empty presets offer nothing to store`() {
        assertEquals("", DnsPreset.System.servers(encrypted = true))
        assertEquals("", DnsPreset.Custom.servers(encrypted = true))
        assertEquals(DnsPreset.System, dnsPresetFor(""))
        assertFalse(DnsPreset.System.supportsEncryption)
        assertFalse(DnsPreset.Custom.supportsEncryption)
    }

    @Test
    fun `every provider is recognised from the value it writes`() {
        providers.forEach { preset ->
            assertEquals(preset, dnsPresetFor(preset.servers(encrypted = false)))
            assertEquals(preset, dnsPresetFor(preset.servers(encrypted = true)))
        }
    }

    @Test
    fun `a provider is recognised from the IPv4-only value an earlier build wrote`() {
        assertEquals(DnsPreset.Cloudflare, dnsPresetFor("1.1.1.1,1.0.0.1"))
        assertEquals(
            DnsPreset.Cloudflare,
            dnsPresetFor("https://1.1.1.1/dns-query,https://1.0.0.1/dns-query"),
        )
    }

    @Test
    fun `an unrecognised list falls back to custom`() {
        assertEquals(DnsPreset.Custom, dnsPresetFor("192.0.2.53"))
        assertEquals(DnsPreset.Custom, dnsPresetFor("1.1.1.1"))
        assertEquals(DnsPreset.Custom, dnsPresetFor("1.1.1.1,8.8.8.8"))
    }

    @Test
    fun `an IPv4-only provider value canonicalises to the full address list`() {
        assertEquals(
            "1.1.1.1,1.0.0.1,2606:4700:4700::1111,2606:4700:4700::1001",
            canonicalDnsServers("1.1.1.1,1.0.0.1"),
        )
        assertEquals(
            "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query," +
                "https://[2606:4700:4700::1111]/dns-query,https://[2606:4700:4700::1001]/dns-query",
            canonicalDnsServers("https://1.1.1.1/dns-query,https://1.0.0.1/dns-query"),
        )
    }

    @Test
    fun `canonicalisation leaves current and custom values alone`() {
        providers.forEach { preset ->
            assertNull(canonicalDnsServers(preset.servers(encrypted = false)))
            assertNull(canonicalDnsServers(preset.servers(encrypted = true)))
        }
        assertNull(canonicalDnsServers("192.0.2.53,198.51.100.53"))
        assertNull(canonicalDnsServers(""))
    }

    @Test
    fun `encryption is detected from the stored endpoints`() {
        assertTrue(isEncryptedDnsValue(DnsPreset.Google.servers(encrypted = true)))
        assertFalse(isEncryptedDnsValue(DnsPreset.Google.servers(encrypted = false)))
        assertFalse(isEncryptedDnsValue(""))
        assertFalse(isEncryptedDnsValue("https://1.1.1.1/dns-query,1.0.0.1"))
    }

    @Test
    fun `the shipped defaults are presets the DNS screen can name`() {
        assertEquals(DnsPreset.Cloudflare, dnsPresetFor(SettingsRepository.DEFAULT_DNS_SERVERS))
        assertTrue(isEncryptedDnsValue(SettingsRepository.DEFAULT_DNS_SERVERS))
        assertEquals(DnsPreset.Yandex, dnsPresetFor(SettingsRepository.DEFAULT_DOMESTIC_DNS_SERVERS))
        assertNull(canonicalDnsServers(SettingsRepository.DEFAULT_DNS_SERVERS))
        assertNull(canonicalDnsServers(SettingsRepository.DEFAULT_DOMESTIC_DNS_SERVERS))
    }
}
