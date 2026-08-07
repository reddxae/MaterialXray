package com.material.xray.model

internal fun normalizeDnsServersForIpv6(servers: String, allowIpv6: Boolean): String {
    val values = servers.commaSeparatedValues()
    if (!allowIpv6) return values.filterNot(::isIpv6DnsServerLiteral).joinToString(",")
    if (values.isEmpty() || values.any(::isIpv6DnsServerLiteral) || !values.all(::isIpv4DnsServerLiteral)) {
        return values.joinToString(",")
    }

    val mappedServers = values.mapNotNull(IPV4_TO_IPV6_DNS::get)
    return (values + mappedServers.ifEmpty { listOf(DEFAULT_IPV6_DNS_SERVER) })
        .distinct()
        .joinToString(",")
}

private fun String.commaSeparatedValues(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty)

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

private const val DEFAULT_IPV6_DNS_SERVER = "2606:4700:4700::1111"
private const val IPV6_GROUP_COUNT = 8
private const val IPV6_GROUP_HEX_LENGTH = 4
private const val IPV4_EMBEDDED_GROUP_COUNT = 2

private val IPV4_TO_IPV6_DNS = mapOf(
    "1.1.1.1" to "2606:4700:4700::1111",
    "1.0.0.1" to "2606:4700:4700::1001",
    "1.1.1.2" to "2606:4700:4700::1112",
    "1.0.0.2" to "2606:4700:4700::1002",
    "1.1.1.3" to "2606:4700:4700::1113",
    "1.0.0.3" to "2606:4700:4700::1003",
    "8.8.8.8" to "2001:4860:4860::8888",
    "8.8.4.4" to "2001:4860:4860::8844",
    "9.9.9.9" to "2620:fe::fe",
    "149.112.112.112" to "2620:fe::9",
    "77.88.8.8" to "2a02:6b8::feed:0ff",
    "77.88.8.1" to "2a02:6b8:0:1::feed:0ff",
    "77.88.8.88" to "2a02:6b8::feed:bad",
    "77.88.8.2" to "2a02:6b8:0:1::feed:bad",
    "77.88.8.7" to "2a02:6b8::feed:a11",
    "77.88.8.3" to "2a02:6b8:0:1::feed:a11",
    "94.140.14.14" to "2a10:50c0::ad1:ff",
    "94.140.15.15" to "2a10:50c0::ad2:ff",
    "208.67.222.222" to "2620:119:35::35",
    "208.67.220.220" to "2620:119:53::53",
)
