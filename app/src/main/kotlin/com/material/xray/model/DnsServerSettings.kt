package com.material.xray.model

/**
 * The resolvers Xray should be given for a stored DNS setting, once the IPv6 preference is applied.
 *
 * The stored value always holds both address families, exactly as the user chose them. Which of
 * them survives into a config is decided here, so flipping the IPv6 preference reroutes the next
 * config rather than rewriting the setting. With IPv6 off the IPv6 resolvers go, because the tunnel
 * carries no IPv6 to reach them over.
 */
internal fun resolveDnsServersForIpv6(servers: String, allowIpv6: Boolean): List<String> {
    val entries = servers.dnsServerEntries()
    return if (allowIpv6) entries else entries.filterNot(::isIpv6DnsServerEndpoint)
}

/** The IPv6 resolvers in [servers], which the DNS screen names when explaining the IPv6 preference. */
internal fun ipv6DnsServers(servers: String): List<String> = servers.dnsServerEntries().filter(::isIpv6DnsServerEndpoint)

private fun isIpv6DnsServerEndpoint(value: String): Boolean {
    val authority = value.substringAfter("://", value).substringBefore('/').substringBefore('?')
    return isIpv6DnsServerLiteral(authority)
}

/** The individual resolver entries in a stored comma-separated DNS setting, blanks discarded. */
internal fun String.dnsServerEntries(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty)

internal fun isIpv4DnsServerLiteral(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
}

internal fun isIpv6DnsServerLiteral(value: String): Boolean {
    val address = if (value.startsWith('[')) {
        value.substringAfter('[').substringBefore(']', missingDelimiterValue = "")
    } else {
        value
    }.substringBefore('%')
    if (address.count { it == ':' } < 2) return false
    if (address.contains('.') && !isIpv4DnsServerLiteral(address.substringAfterLast(':'))) return false

    val compressionIndex = address.indexOf("::")
    if (compressionIndex >= 0 && address.indexOf("::", compressionIndex + 2) >= 0) return false

    val groups = if (compressionIndex >= 0) {
        val leadingGroups = address.substring(0, compressionIndex).ipv6GroupCount() ?: return false
        val trailingGroups = address.substring(compressionIndex + 2).ipv6GroupCount() ?: return false
        leadingGroups + trailingGroups
    } else {
        address.ipv6GroupCount() ?: return false
    }

    return if (compressionIndex >= 0) groups < IPV6_GROUP_COUNT else groups == IPV6_GROUP_COUNT
}

private fun String.ipv6GroupCount(): Int? {
    if (isEmpty()) return 0
    val groups = split(':')
    if (groups.any(String::isEmpty)) return null

    return groups.mapIndexed { index, group ->
        when {
            group.contains('.') && index == groups.lastIndex && isIpv4DnsServerLiteral(group) -> IPV4_EMBEDDED_GROUP_COUNT
            group.length in 1..IPV6_GROUP_HEX_LENGTH && group.all(Char::isHexDigit) -> 1
            else -> return null
        }
    }.sum()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val IPV6_GROUP_COUNT = 8
private const val IPV6_GROUP_HEX_LENGTH = 4
private const val IPV4_EMBEDDED_GROUP_COUNT = 2
