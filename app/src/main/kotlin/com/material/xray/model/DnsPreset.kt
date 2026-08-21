package com.material.xray.model

import androidx.annotation.StringRes
import com.material.xray.R

/**
 * A named set of resolvers the DNS screen can write into a DNS setting.
 *
 * A preset stores nothing of its own. It is a way to produce a value for [servers] and, going the
 * other way, to recognise a stored value with [dnsPresetFor]. That keeps the stored setting a plain
 * resolver list, so a value typed by hand and a value picked from the list are the same kind of
 * thing.
 *
 * Both address families are written into the setting up front. Which of them Xray is actually given
 * is decided when a config is built, by [resolveDnsServersForIpv6], so flipping the IPv6 preference
 * never rewrites what the user picked.
 *
 * @property ipv4Servers the resolver IPv4 literals, in the order Xray should try them.
 * @property ipv6Servers the same resolvers over IPv6, dropped from the generated config when IPv6
 *   connections are off.
 * @property encryptsIpv6 whether the provider's certificate names its IPv6 addresses. Xray verifies
 *   a DoH endpoint against the address in the URL, so a provider that names only IPv4 there can
 *   only be encrypted over IPv4.
 */
enum class DnsPreset(
    val ipv4Servers: List<String>,
    val ipv6Servers: List<String>,
    val encryptsIpv6: Boolean,
    @param:StringRes val labelResource: Int,
    @param:StringRes val descriptionResource: Int,
) {
    /** No resolver of its own. An empty DNS setting makes Xray fall back to the network's resolver. */
    System(
        ipv4Servers = emptyList(),
        ipv6Servers = emptyList(),
        encryptsIpv6 = false,
        labelResource = R.string.dns_preset_system_label,
        descriptionResource = R.string.dns_preset_system_description,
    ),
    Cloudflare(
        ipv4Servers = listOf("1.1.1.1", "1.0.0.1"),
        ipv6Servers = listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
        encryptsIpv6 = true,
        labelResource = R.string.dns_preset_cloudflare_label,
        descriptionResource = R.string.dns_preset_cloudflare_description,
    ),
    CloudflareSecurity(
        ipv4Servers = listOf("1.1.1.2", "1.0.0.2"),
        ipv6Servers = listOf("2606:4700:4700::1112", "2606:4700:4700::1002"),
        encryptsIpv6 = true,
        labelResource = R.string.dns_preset_cloudflare_security_label,
        descriptionResource = R.string.dns_preset_cloudflare_security_description,
    ),
    Google(
        ipv4Servers = listOf("8.8.8.8", "8.8.4.4"),
        ipv6Servers = listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
        encryptsIpv6 = true,
        labelResource = R.string.dns_preset_google_label,
        descriptionResource = R.string.dns_preset_google_description,
    ),
    Quad9(
        ipv4Servers = listOf("9.9.9.9", "149.112.112.112"),
        ipv6Servers = listOf("2620:fe::fe", "2620:fe::9"),
        encryptsIpv6 = true,
        labelResource = R.string.dns_preset_quad9_label,
        descriptionResource = R.string.dns_preset_quad9_description,
    ),
    AdGuard(
        ipv4Servers = listOf("94.140.14.14", "94.140.15.15"),
        ipv6Servers = listOf("2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
        // dns.adguard-dns.com names six IPv4 addresses and no IPv6 address.
        encryptsIpv6 = false,
        labelResource = R.string.dns_preset_adguard_label,
        descriptionResource = R.string.dns_preset_adguard_description,
    ),
    Yandex(
        ipv4Servers = listOf("77.88.8.8", "77.88.8.1"),
        ipv6Servers = listOf("2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff"),
        // The 77.88.8.x certificate names those IPv4 addresses and *.dot.dns.yandex.net, no IPv6.
        encryptsIpv6 = false,
        labelResource = R.string.dns_preset_yandex_label,
        descriptionResource = R.string.dns_preset_yandex_description,
    ),

    /** Whatever the user typed. Carries no addresses of its own; the stored value is the answer. */
    Custom(
        ipv4Servers = emptyList(),
        ipv6Servers = emptyList(),
        encryptsIpv6 = false,
        labelResource = R.string.dns_preset_custom_label,
        descriptionResource = R.string.dns_preset_custom_description,
    ),
    ;

    /** Whether this preset can offer DNS-over-HTTPS endpoints at all. */
    val supportsEncryption: Boolean get() = ipv4Servers.isNotEmpty()

    /**
     * The value to store for this preset.
     *
     * When [encrypted], every address the provider's certificate covers becomes a DoH URL and the
     * rest are dropped, because a list mixing encrypted and plaintext resolvers would leak the
     * queries it silently fell back to.
     */
    fun servers(encrypted: Boolean): String = when {
        !supportsEncryption -> ""
        !encrypted -> (ipv4Servers + ipv6Servers).joinToString(",")
        encryptsIpv6 -> (ipv4Servers + ipv6Servers).joinToString(",", transform = ::dnsOverHttpsUrl)
        else -> ipv4Servers.joinToString(",", transform = ::dnsOverHttpsUrl)
    }

    /** Every form of this preset a stored setting could hold, current or from an earlier build. */
    internal fun storedForms(): List<String> = listOf(
        servers(encrypted = false),
        servers(encrypted = true),
        // Earlier builds kept IPv6 out of the setting whenever IPv6 connections were off.
        ipv4Servers.joinToString(","),
        ipv4Servers.joinToString(",", transform = ::dnsOverHttpsUrl),
    )
}

private fun dnsOverHttpsUrl(address: String): String = if (isIpv6DnsServerLiteral(address)) "https://[$address]/dns-query" else "https://$address/dns-query"

/**
 * The preset [servers] was written from, or [DnsPreset.Custom] when it matches none of them.
 *
 * A setting written by an earlier build is recognised too, so an install that never ran the
 * canonicalising migration still sees its provider by name rather than as a custom list.
 */
fun dnsPresetFor(servers: String): DnsPreset {
    val entries = servers.dnsServerEntries()
    if (entries.isEmpty()) return DnsPreset.System

    return DnsPreset.entries.firstOrNull { preset ->
        preset.supportsEncryption && preset.storedForms().any { it.dnsServerEntries() == entries }
    } ?: DnsPreset.Custom
}

/**
 * [servers] rewritten to the current form of the preset it came from, or null when it is already
 * current or belongs to no preset.
 *
 * This is how a setting written before the addresses moved into the presets picks up the IPv6
 * resolvers it was missing.
 */
internal fun canonicalDnsServers(servers: String): String? {
    val preset = dnsPresetFor(servers)
    if (!preset.supportsEncryption) return null

    val canonical = preset.servers(encrypted = isEncryptedDnsValue(servers))
    return canonical.takeIf { it.dnsServerEntries() != servers.dnsServerEntries() }
}

/** Whether [servers] holds DNS-over-HTTPS endpoints rather than plain resolver addresses. */
fun isEncryptedDnsValue(servers: String): Boolean {
    val entries = servers.dnsServerEntries()
    return entries.isNotEmpty() && entries.all { it.startsWith("https://", ignoreCase = true) }
}
