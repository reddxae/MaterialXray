package com.material.xray.data.parser

import com.material.xray.model.RoutingRule
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

    fun parse(headers: Headers): SubscriptionRouting? {
        val header = SubscriptionStandardHeaders.normalizeNullableHeader(
            headers[SubscriptionStandardHeaders.ROUTING],
        ) ?: return null
        val payload = when {
            header.startsWith("{") -> header
            header.startsWith(BASE64_PREFIX, ignoreCase = true) ->
                SubscriptionStandardHeaders.decodeBase64ToUtf8(header.substring(BASE64_PREFIX.length))
            else -> SubscriptionStandardHeaders.decodeBase64ToUtf8(header)
        } ?: return null
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val rules = (root["rules"] as? JsonArray)
            .orEmpty()
            .mapIndexedNotNull(::parseRule)

        return SubscriptionRouting(
            rules = rules,
            domainStrategy = root.string("domainStrategy") ?: SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
            domainMatcher = root.string("domainMatcher"),
        ).normalized()
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
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.let(::listOf).orEmpty()
        else -> emptyList()
    }
}
