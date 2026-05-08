package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val defaultServers = servers.commaSeparatedValues()
    if (!allowIpv6) {
        put("queryStrategy", "UseIPv4")
    }
    put("servers", buildJsonArray {
        if (defaultServers.isEmpty()) {
            add(SYSTEM_DNS_SERVER)
        } else {
            defaultServers.forEach { add(it) }
        }
        if (domesticDomains.isNotEmpty()) {
            domesticServers.commaSeparatedValues().forEach { domesticServer ->
                add(buildJsonObject {
                    put("address", domesticServer)
                    put("domains", buildJsonArray { domesticDomains.forEach { add(it) } })
                    put("skipFallback", true)
                    put("tag", DOMESTIC_DNS_TAG)
                })
            }
        }
    })
}

internal fun buildRouting(
    routingRules: List<RoutingRule>,
    appProxyRoutes: List<AppProxyRoute> = emptyList(),
    bypassLan: Boolean = true,
    domesticDnsServers: String = "",
) = buildJsonObject {
    put("domainStrategy", "IPOnDemand")
    put("rules", buildJsonArray {
        add(dnsRoutingRule(appProxyRoutes))
        if (domesticDnsServers.isNotBlank()) {
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
    })
}

private fun directDomains(routingRules: List<RoutingRule>, bypassLan: Boolean): List<String> =
    buildSet {
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
    put("inboundTag", buildJsonArray {
        add("tun-in")
        appProxyRoutes.forEach { add(it.inboundTag) }
    })
    put("port", "53")
    put("outboundTag", "dns-out")
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
        rules += JsonObject(base().apply {
            put("domain", buildJsonArray { domains.forEach { add(it) } })
        })
    }
    rule.ips.cleanEntries().takeIf { it.isNotEmpty() }?.let { ips ->
        rules += JsonObject(base().apply {
            put("ip", buildJsonArray { ips.forEach { add(it) } })
        })
    }
    rule.port?.takeIf { it.isNotBlank() }?.let { port ->
        rules += JsonObject(base().apply {
            put("port", JsonPrimitive(port))
        })
    }
    rule.protocols.takeIf { it.isNotEmpty() }?.let { protocols ->
        rules += JsonObject(base().apply {
            put("protocol", buildJsonArray { protocols.forEach { add(it) } })
        })
    }

    if (rules.isEmpty()) {
        rules += JsonObject(base())
    }

    return rules
}

private fun String.commaSeparatedValues(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun List<String>.cleanEntries(): List<String> =
    map { it.trim() }.filter { it.isNotEmpty() }
