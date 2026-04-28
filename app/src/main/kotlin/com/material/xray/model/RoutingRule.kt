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
    fun contentText(): String = buildList {
        if (domains.isNotEmpty()) add("Domain: ${domains.joinToString(", ")}")
        if (ips.isNotEmpty()) add("IP: ${ips.joinToString(", ")}")
        port?.takeIf { it.isNotBlank() }?.let { add("Port: $it") }
        if (protocols.isNotEmpty()) add("Protocol: ${protocols.joinToString(", ")}")
    }.joinToString("\n")

    fun toXrayRule(): JsonObject = buildJsonObject {
        put("type", "field")
        put("outboundTag", outboundTag)
        if (domains.isNotEmpty()) put("domain", domains.asJsonArray())
        if (ips.isNotEmpty()) put("ip", ips.asJsonArray())
        port?.takeIf { it.isNotBlank() }?.let { put("port", it) }
        if (protocols.isNotEmpty()) put("protocol", protocols.asJsonArray())
    }

    private fun List<String>.asJsonArray(): JsonArray = buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }
}

object RoutingRuleCatalog {
    fun defaults(): List<RoutingRule> = listOf(
        RoutingRule(
            id = "ru-direct",
            name = "RU Direct",
            outboundTag = "direct",
            domains = listOf("domain:ru"),
            ips = listOf("geoip:ru"),
            operator = RoutingRuleOperator.OR,
        ),
        RoutingRule(
            id = "block-ads",
            name = "Block Ads",
            outboundTag = "block",
            domains = listOf("geosite:category-ads-all"),
            enabled = false,
        ),
    )

    fun mergeWithDefaults(savedRules: List<RoutingRule>): List<RoutingRule> {
        val normalizedRules = savedRules.map { rule ->
            if (rule.id == "ru-direct" && rule.domains == listOf("domain:ru", "geosite:ru")) {
                rule.copy(domains = listOf("domain:ru"))
            } else {
                rule
            }
        }.filterNot { rule ->
            rule.id == "default-proxy" ||
                rule.id == "lan-ip-direct" ||
                rule.id == "lan-domain-direct"
        }
        val existingIds = normalizedRules.mapTo(mutableSetOf()) { it.id }
        val missingDefaultRules = defaults().filterNot { it.id in existingIds }
        if (missingDefaultRules.isEmpty()) return normalizedRules
        return normalizedRules + missingDefaultRules
    }
}
