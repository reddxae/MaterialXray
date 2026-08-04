package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.SubscriptionRouting
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DEFAULT_DNS_TAG = "default-dns"
private const val DOMESTIC_DNS_TAG = "domestic-dns"
private const val SYSTEM_DNS_SERVER = "localhost"

internal fun buildDns(
    servers: String,
    domesticServers: String = "",
    routingRules: List<RoutingRule> = emptyList(),
    bypassLan: Boolean = false,
    allowIpv6: Boolean = false,
) = buildJsonObject {
    val domesticDomains = directDomains(routingRules, bypassLan)
    val defaultServers = servers.commaSeparatedValues().applyIpv6DnsPolicy(allowIpv6)
    if (!allowIpv6) {
        put("queryStrategy", "UseIPv4")
    }
    if (defaultServers.isNotEmpty()) {
        put("tag", DEFAULT_DNS_TAG)
    }
    put(
        "servers",
        buildJsonArray {
            if (defaultServers.isEmpty()) {
                add(SYSTEM_DNS_SERVER)
            } else {
                defaultServers.forEach { add(it) }
            }
            if (domesticDomains.isNotEmpty()) {
                domesticServers.commaSeparatedValues().applyIpv6DnsPolicy(allowIpv6).forEach { domesticServer ->
                    add(
                        buildJsonObject {
                            put("address", domesticServer)
                            put("domains", buildJsonArray { domesticDomains.forEach { add(it) } })
                            put("skipFallback", true)
                            put("tag", DOMESTIC_DNS_TAG)
                        },
                    )
                }
            }
        },
    )
}

internal fun buildRouting(
    routingRules: List<RoutingRule>,
    appProxyRoutes: List<AppProxyRoute> = emptyList(),
    bypassLan: Boolean = true,
    dnsServers: String = "",
    domesticDnsServers: String = "",
    domainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
    domainMatcher: String? = null,
    allowIpv6: Boolean = false,
) = buildJsonObject {
    val hasDomesticDomains = directDomains(routingRules, bypassLan).isNotEmpty()
    put("domainStrategy", SubscriptionRouting.normalizeDomainStrategy(domainStrategy))
    SubscriptionRouting.normalizeDomainMatcher(domainMatcher)?.let { put("domainMatcher", it) }
    put(
        "rules",
        buildJsonArray {
            add(dnsRoutingRule(appProxyRoutes))
            add(dnsOverTlsRoutingRule(appProxyRoutes))
            if (dnsServers.commaSeparatedValues().applyIpv6DnsPolicy(allowIpv6).isNotEmpty()) {
                add(defaultDnsRoutingRule())
            }
            if (
                hasDomesticDomains &&
                domesticDnsServers.commaSeparatedValues().applyIpv6DnsPolicy(allowIpv6).isNotEmpty()
            ) {
                add(domesticDnsRoutingRule())
            }
            appProxyRoutes.filterNot { it.applyRoutingRules }.forEach { route ->
                add(appProxyRoutingRule(route.inboundTag, route.outboundTag))
            }
            if (bypassLan) {
                add(lanIpRoutingRule())
                add(lanDomainRoutingRule())
            }
            routingRules.filter { it.enabled }.forEach { rule ->
                if (rule.operator == RoutingRuleOperator.OR) {
                    buildOrRules(rule).forEach { add(it) }
                } else {
                    add(rule.toXrayRule())
                }
            }
            appProxyRoutes.filter { it.applyRoutingRules }.forEach { route ->
                add(appProxyRoutingRule(route.inboundTag, route.outboundTag))
            }
        },
    )
}

private fun directDomains(routingRules: List<RoutingRule>, bypassLan: Boolean): List<String> = buildSet {
    if (bypassLan) add("geosite:private")
    routingRules
        .asSequence()
        .filter { it.enabled && it.outboundTag == "direct" }
        .flatMap { it.domains.asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach(::add)
}.toList()

private fun dnsRoutingRule(appProxyRoutes: List<AppProxyRoute>) = buildJsonObject {
    put("type", "field")
    put(
        "inboundTag",
        buildJsonArray {
            add("tun-in")
            appProxyRoutes.forEach { add(it.inboundTag) }
        },
    )
    put("port", "53")
    put("outboundTag", "dns-out")
}

private fun dnsOverTlsRoutingRule(appProxyRoutes: List<AppProxyRoute>) = buildJsonObject {
    put("type", "field")
    put(
        "inboundTag",
        buildJsonArray {
            add("tun-in")
            appProxyRoutes.forEach { add(it.inboundTag) }
        },
    )
    put("network", "tcp")
    put("port", "853")
    put("outboundTag", "direct")
}

private fun defaultDnsRoutingRule() = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(DEFAULT_DNS_TAG) })
    put("outboundTag", "direct")
}

private fun domesticDnsRoutingRule() = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(DOMESTIC_DNS_TAG) })
    put("outboundTag", "direct")
}

private fun appProxyRoutingRule(inboundTag: String, outboundTag: String) = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(inboundTag) })
    put("outboundTag", outboundTag)
}

private fun lanIpRoutingRule() = buildJsonObject {
    put("type", "field")
    put("ip", buildJsonArray { add("geoip:private") })
    put("outboundTag", "direct")
}

private fun lanDomainRoutingRule() = buildJsonObject {
    put("type", "field")
    put("domain", buildJsonArray { add("geosite:private") })
    put("outboundTag", "direct")
}

private fun buildOrRules(rule: RoutingRule): List<JsonObject> {
    fun base(): MutableMap<String, JsonElement> = mutableMapOf(
        "type" to JsonPrimitive("field"),
        "outboundTag" to JsonPrimitive(rule.outboundTag),
    )

    val rules = mutableListOf<JsonObject>()
    rule.domains.cleanEntries().takeIf { it.isNotEmpty() }?.let { domains ->
        rules += JsonObject(
            base().apply {
                put("domain", buildJsonArray { domains.forEach { add(it) } })
            },
        )
    }
    rule.ips.cleanEntries().takeIf { it.isNotEmpty() }?.let { ips ->
        rules += JsonObject(
            base().apply {
                put("ip", buildJsonArray { ips.forEach { add(it) } })
            },
        )
    }
    rule.port?.takeIf { it.isNotBlank() }?.let { port ->
        rules += JsonObject(
            base().apply {
                put("port", JsonPrimitive(port))
            },
        )
    }
    rule.protocols.takeIf { it.isNotEmpty() }?.let { protocols ->
        rules += JsonObject(
            base().apply {
                put("protocol", buildJsonArray { protocols.forEach { add(it) } })
            },
        )
    }

    if (rules.isEmpty()) {
        rules += JsonObject(base())
    }

    return rules
}

private fun String.commaSeparatedValues(): List<String> = split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun List<String>.applyIpv6DnsPolicy(allowIpv6: Boolean): List<String> {
    if (!allowIpv6) return filterNot(::isIpv6Literal)
    if (isEmpty() || any(::isIpv6Literal) || !all(::isIpv4Literal)) return this

    val mappedServers = mapNotNull(IPV4_TO_IPV6_DNS::get)
    return (this + mappedServers.ifEmpty { listOf(DEFAULT_IPV6_DNS_SERVER) }).distinct()
}

private fun isIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
}

private fun isIpv6Literal(value: String): Boolean {
    val address = if (value.startsWith('[')) {
        value.substringAfter('[').substringBefore(']', missingDelimiterValue = "")
    } else {
        value
    }.substringBefore('%')
    if (address.count { it == ':' } < 2) return false
    if (address.contains('.') && !isIpv4Literal(address.substringAfterLast(':'))) return false

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
            group.contains('.') && index == groups.lastIndex && isIpv4Literal(group) -> IPV4_EMBEDDED_GROUP_COUNT
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

private fun List<String>.cleanEntries(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }
