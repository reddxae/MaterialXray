package com.material.xray.data.parser

import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.SubscriptionRouting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers

object SubscriptionRoutingHeaderParser {
    private const val BASE64_PREFIX = "base64:"
    private const val HAPP_DEFAULT_DOMAIN_STRATEGY = "IPIfNonMatch"
    private const val HAPP_ROUTING_PREFIX = "happ://routing/"
    private val happPayloadPrefixes = listOf(
        "${HAPP_ROUTING_PREFIX}add/",
        "${HAPP_ROUTING_PREFIX}onadd/",
    )
    private val happRuleGroups = listOf(
        HappRuleGroup("block", "Block", "BlockSites", "BlockIp"),
        HappRuleGroup("proxy", "Proxy", "ProxySites", "ProxyIp"),
        HappRuleGroup("direct", "Direct", "DirectSites", "DirectIp"),
    )
    private val happRuleGroupsByTag = happRuleGroups.associateBy(HappRuleGroup::outboundTag)
    private val unsupportedRuleFields = setOf(
        "attrs",
        "balancerTag",
        "inboundTag",
        "network",
        "source",
        "sourcePort",
        "user",
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val routingDisabledValues = setOf("0", "false", "off", "no")

    fun parse(headers: Headers): SubscriptionRouting? {
        if (!routingEnabled(headers)) return null
        val header = SubscriptionStandardHeaders.normalizeNullableHeader(
            headers[SubscriptionStandardHeaders.ROUTING],
        ) ?: return null
        if (header.startsWith(HAPP_ROUTING_PREFIX, ignoreCase = true)) {
            return parseHappHeader(header)
        }
        val payload = when {
            header.startsWith("{") -> header
            header.startsWith(BASE64_PREFIX, ignoreCase = true) ->
                SubscriptionStandardHeaders.decodeBase64ToUtf8(header.substring(BASE64_PREFIX.length))
            else -> SubscriptionStandardHeaders.decodeBase64ToUtf8(header)
        } ?: return null
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        return parseXrayRouting(root)
    }

    // Happ's `routing-enable: 0` tells the client the provider does not want the routing header
    // imported on this subscription. Happ's own docs say "any other non-empty value disables", but
    // unknown truthy spellings are far more likely to be data-entry noise than a real opt-out, so
    // only explicit falsy values disable here and absence stays enabled.
    private fun routingEnabled(headers: Headers): Boolean {
        val raw = headers[SubscriptionStandardHeaders.ROUTING_ENABLE] ?: return true
        val normalized = SubscriptionStandardHeaders.normalizeNullableHeader(raw) ?: return true
        return normalized.lowercase() !in routingDisabledValues
    }

    private fun parseHappHeader(header: String): SubscriptionRouting? {
        val prefix = happPayloadPrefixes.firstOrNull { header.startsWith(it, ignoreCase = true) } ?: return null
        val payload = SubscriptionStandardHeaders.decodeBase64ToUtf8(header.substring(prefix.length)) ?: return null
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val profileName = root.string("Name")
        val rules = root.happRouteOrder().mapNotNull { group ->
            val domains = root.stringList(group.domainKey)
            val ips = root.stringList(group.ipKey)
            if (domains.isEmpty() && ips.isEmpty()) return@mapNotNull null

            RoutingRule(
                id = "happ-${group.outboundTag}",
                name = profileName?.let { "$it: ${group.label}" } ?: "HAPP ${group.label}",
                outboundTag = group.outboundTag,
                domains = domains,
                ips = ips,
                operator = RoutingRuleOperator.OR,
            )
        }

        return SubscriptionRouting(
            rules = rules,
            domainStrategy = SubscriptionRouting.normalizeDomainStrategy(
                root.string("DomainStrategy"),
                default = HAPP_DEFAULT_DOMAIN_STRATEGY,
            ),
            fallbackOutboundTag = if (root.string("GlobalProxy")?.toBooleanStrictOrNull() == false) "direct" else "proxy",
        ).normalized()
    }

    private fun parseXrayRouting(root: JsonObject): SubscriptionRouting {
        val rules = (root["rules"] as? JsonArray)
            .orEmpty()
            .mapIndexedNotNull(::parseRule)

        return SubscriptionRouting(
            rules = rules,
            domainStrategy = root.string("domainStrategy") ?: SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
            domainMatcher = root.string("domainMatcher"),
        ).normalized()
    }

    private fun JsonObject.happRouteOrder(): List<HappRuleGroup> {
        val tags = string("RouteOrder")
            ?.lowercase()
            ?.split('-')
            ?.map(String::trim)
            ?.takeIf { it.size == happRuleGroups.size && it.toSet() == happRuleGroupsByTag.keys }
            ?: happRuleGroups.map(HappRuleGroup::outboundTag)
        return tags.mapNotNull(happRuleGroupsByTag::get)
    }

    private fun parseRule(index: Int, element: JsonElement): RoutingRule? {
        val rule = element as? JsonObject ?: return null
        if (rule.string("type")?.equals("field", ignoreCase = true) != true) return null
        if (rule.keys.any { it in unsupportedRuleFields }) return null
        val outboundTag = rule.string("outboundTag") ?: return null
        return RoutingRule(
            id = rule.string("id") ?: "subscription-rule-${index + 1}",
            name = rule.string("__name__") ?: rule.string("name") ?: "Rule ${index + 1}",
            outboundTag = outboundTag,
            domains = rule.stringList("domain"),
            ips = rule.stringList("ip"),
            port = rule.string("port"),
            protocols = rule.stringList("protocol"),
            enabled = (rule["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    private data class HappRuleGroup(
        val outboundTag: String,
        val label: String,
        val domainKey: String,
        val ipKey: String,
    )
}
