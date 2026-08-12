package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.isIpv4DnsServerLiteral
import com.material.xray.model.isIpv6DnsServerLiteral
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

internal sealed interface XrayRouteTarget {
    data class Outbound(val tag: String) : XrayRouteTarget

    data class Balancer(val tag: String) : XrayRouteTarget
}

internal fun buildDns(
    servers: String,
    domesticServers: String = "",
    bootstrapHosts: Map<String, List<String>> = emptyMap(),
    routingRules: List<RoutingRule> = emptyList(),
    bypassLan: Boolean = false,
    allowIpv6: Boolean = false,
) = buildJsonObject {
    val domesticDomains = directDomains(routingRules, bypassLan)
    val defaultServers = servers.commaSeparatedValues().map(String::toReliableXrayDnsAddress)
    if (!allowIpv6) {
        put("queryStrategy", "UseIPv4")
    }
    if (defaultServers.isNotEmpty()) {
        put("tag", DEFAULT_DNS_TAG)
    }
    if (bootstrapHosts.isNotEmpty()) {
        put(
            "hosts",
            buildJsonObject {
                bootstrapHosts.toSortedMap().forEach { (host, addresses) ->
                    put(host, buildJsonArray { addresses.distinct().forEach { add(it) } })
                }
            },
        )
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
                domesticServers.commaSeparatedValues().map(String::toReliableXrayDnsAddress).forEach { domesticServer ->
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
    defaultRouteTarget: XrayRouteTarget = XrayRouteTarget.Outbound("proxy"),
    dataInboundTags: List<String> = listOf("tun-in") + appProxyRoutes.map { it.inboundTag },
) = buildJsonObject {
    val hasDomesticDomains = directDomains(routingRules, bypassLan).isNotEmpty()
    put("domainStrategy", SubscriptionRouting.normalizeDomainStrategy(domainStrategy))
    SubscriptionRouting.normalizeDomainMatcher(domainMatcher)?.let { put("domainMatcher", it) }
    put(
        "rules",
        buildJsonArray {
            add(dnsRoutingRule(dataInboundTags))
            add(dnsOverTlsRoutingRule(dataInboundTags))
            if (dnsServers.commaSeparatedValues().isNotEmpty()) {
                add(defaultDnsRoutingRule(defaultRouteTarget))
            }
            if (
                hasDomesticDomains &&
                domesticDnsServers.commaSeparatedValues().isNotEmpty()
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
                add(appProxyRoutingRule(route.inboundTag, defaultRouteTarget))
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

private fun dnsRoutingRule(dataInboundTags: List<String>) = buildJsonObject {
    put("type", "field")
    put(
        "inboundTag",
        buildJsonArray {
            dataInboundTags.forEach { add(it) }
        },
    )
    put("port", "53")
    put("outboundTag", "dns-out")
}

private fun dnsOverTlsRoutingRule(dataInboundTags: List<String>) = buildJsonObject {
    put("type", "field")
    put(
        "inboundTag",
        buildJsonArray {
            dataInboundTags.forEach { add(it) }
        },
    )
    put("network", "tcp")
    put("port", "853")
    put("outboundTag", "direct")
}

private fun defaultDnsRoutingRule(target: XrayRouteTarget) = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(DEFAULT_DNS_TAG) })
    when (target) {
        is XrayRouteTarget.Outbound -> put("outboundTag", target.tag)
        is XrayRouteTarget.Balancer -> put("balancerTag", target.tag)
    }
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

private fun appProxyRoutingRule(inboundTag: String, target: XrayRouteTarget) = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(inboundTag) })
    when (target) {
        is XrayRouteTarget.Outbound -> put("outboundTag", target.tag)
        is XrayRouteTarget.Balancer -> put("balancerTag", target.tag)
    }
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
    rule.protocols.cleanEntries().takeIf { it.isNotEmpty() }?.let { protocols ->
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

// Xray-core #2248 can permanently cache empty Cloudflare UDP responses for individual names, so every
// bare Cloudflare resolver literal is rewritten to a non-UDP endpoint. Cloudflare serves its resolver
// IPs in the DoH certificate, so an address without an explicit port becomes a DoH URL; an address that
// pins a port keeps that port over TCP because DoH cannot honour it.
private fun String.toReliableXrayDnsAddress(): String {
    val host = numericDnsHostOrNull() ?: return this
    if (host !in CLOUDFLARE_IPV4_DNS && !host.lowercase().startsWith(CLOUDFLARE_IPV6_DNS_PREFIX)) return this

    val escapedAddress = replace("%", "%25")
    return when {
        hasExplicitDnsPort() -> "tcp://$escapedAddress"
        isIpv4DnsServerLiteral(this) || startsWith('[') -> "https://$escapedAddress/dns-query"
        else -> "https://[$escapedAddress]/dns-query"
    }
}

private fun String.hasExplicitDnsPort(): Boolean = isIpv4DnsServerWithPort() || (startsWith('[') && substringAfter(']').isNotEmpty())

private fun String.numericDnsHostOrNull(): String? = when {
    isIpv4DnsServerLiteral(this) -> this
    isIpv4DnsServerWithPort() -> substringBeforeLast(':')
    isIpv6DnsServerLiteral(this) && startsWith('[') -> substringAfter('[').substringBefore(']').substringBefore('%')
    isIpv6DnsServerLiteral(this) -> substringBefore('%')
    else -> null
}

private fun String.isIpv4DnsServerWithPort(): Boolean {
    if (count { it == ':' } != 1) return false
    val host = substringBeforeLast(':')
    val port = substringAfterLast(':').toIntOrNull()
    return isIpv4DnsServerLiteral(host) && port != null && port in 1..65535
}

private val CLOUDFLARE_IPV4_DNS = setOf("1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3")
private const val CLOUDFLARE_IPV6_DNS_PREFIX = "2606:4700:4700::"

private fun List<String>.cleanEntries(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }
