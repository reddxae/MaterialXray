package com.material.xray.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class RoutingRuleOperator {
    AND,
    OR,
}

@Serializable
data class RoutingRule(
    val id: String,
    val name: String,
    val outboundTag: String,
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val port: String? = null,
    val protocols: List<String> = emptyList(),
    val operator: RoutingRuleOperator = RoutingRuleOperator.AND,
    val enabled: Boolean = true,
) {
    fun toXrayRule(): JsonObject = buildJsonObject {
        val cleanDomains = domains.cleanEntries()
        val cleanIps = ips.cleanEntries()
        val cleanProtocols = protocols.cleanEntries()
        put("type", "field")
        put("outboundTag", outboundTag)
        if (cleanDomains.isNotEmpty()) put("domain", cleanDomains.asJsonArray())
        if (cleanIps.isNotEmpty()) put("ip", cleanIps.asJsonArray())
        port?.takeIf { it.isNotBlank() }?.let { put("port", it) }
        if (cleanProtocols.isNotEmpty()) put("protocol", cleanProtocols.asJsonArray())
    }

    /**
     * Reports whether this rule carries no matching condition at all.
     *
     * Xray rules match on domains, IPs, ports, or protocols. When none of those are set, both
     * [toXrayRule] and the OR expansion emit a rule that contains only `type` and `outboundTag`,
     * which Xray treats as matching every connection and routes it to [outboundTag]. Because
     * routing rules are evaluated in order, such a rule also shadows every rule after it.
     */
    fun matchesAllTraffic(): Boolean = domains.cleanEntries().isEmpty() &&
        ips.cleanEntries().isEmpty() &&
        port.isNullOrBlank() &&
        protocols.cleanEntries().isEmpty()

    private fun List<String>.cleanEntries(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }

    private fun List<String>.asJsonArray(): JsonArray = buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }
}

object RoutingRuleCatalog {
    private val ruDirectDomains = listOf(
        "domain:ru",
        "domain:su",
        "domain:xn--p1ai",
        "geosite:category-ru",
    )
    private val ruDirectIps = listOf("geoip:ru")

    fun defaults(): List<RoutingRule> = listOf(
        RoutingRule(
            id = "ru-direct",
            name = "Bypass Russian domains and IPs",
            outboundTag = "direct",
            domains = ruDirectDomains,
            ips = ruDirectIps,
            operator = RoutingRuleOperator.OR,
        ),
        RoutingRule(
            id = "block-ads",
            name = "Block ads",
            outboundTag = "block",
            domains = listOf("geosite:category-ads-all"),
            enabled = false,
        ),
    )

    fun defaultIds(): Set<String> = defaults().mapTo(mutableSetOf()) { it.id }

    fun mergeWithDefaults(savedRules: List<RoutingRule>, deletedDefaultRuleIds: Set<String> = emptySet()): List<RoutingRule> {
        val normalizedRules = savedRules.map { rule ->
            if (rule.isUntouchedLegacyRuDirect()) {
                rule.copy(domains = ruDirectDomains, ips = ruDirectIps)
            } else {
                rule
            }
        }.filterNot { rule ->
            rule.id == "default-proxy" ||
                rule.id == "lan-ip-direct" ||
                rule.id == "lan-domain-direct"
        }
        val existingIds = normalizedRules.mapTo(mutableSetOf()) { it.id }
        val missingDefaultRules = defaults().filterNot { it.id in existingIds || it.id in deletedDefaultRuleIds }
        if (missingDefaultRules.isEmpty()) return normalizedRules
        return normalizedRules + missingDefaultRules
    }

    private fun RoutingRule.isUntouchedLegacyRuDirect(): Boolean = id == "ru-direct" &&
        name == "Bypass Russian domains and IPs" &&
        outboundTag == "direct" &&
        ips == ruDirectIps &&
        domains in listOf(
            listOf("domain:ru"),
            listOf("domain:ru", "geosite:ru"),
        )
}
