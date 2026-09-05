package com.material.xray.model

import java.net.InetAddress
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The pool eligible for new connections, not a list of servers carrying existing traffic. */
internal data class ActiveBalancerSelection(
    val outbounds: List<BalancerOutbound> = emptyList(),
) {
    val latencyMs: Long?
        get() {
            if (outbounds.isEmpty()) return null
            // A partial average would imply the whole pool was measured successfully.
            val latencies = outbounds.map { it.latencyMs ?: return null }
            return latencies.average().roundToLong()
        }
}

internal data class BalancerOutbound(
    val outboundTag: String,
    val latencyMs: Long?,
)

internal fun ServerConfig.primaryBalancerTag(): String? {
    val root = rawConfigRoot() ?: return null
    val routing = root["routing"] as? JsonObject ?: return null
    val balancerTags = (routing["balancers"] as? JsonArray)
        .orEmpty()
        .mapNotNull { balancer -> (balancer as? JsonObject)?.string("tag") }
    if (balancerTags.isEmpty()) return null

    val configuredTags = balancerTags.toSet()
    return (routing["rules"] as? JsonArray)
        .orEmpty()
        .mapNotNull { rule -> (rule as? JsonObject)?.string("balancerTag") }
        .lastOrNull { it in configuredTags }
        ?: balancerTags.first()
}

internal fun ServerConfig.matchesBalancerOutbound(outboundTag: String, candidate: ServerConfig): Boolean {
    val selectedOutbound = rawProxyOutbounds().firstOrNull { it.string("tag") == outboundTag } ?: return false
    val candidateOutbounds = candidate.rawProxyOutbounds()
    val candidateOutbound = candidateOutbounds.firstOrNull { it.string("tag").equals("proxy", ignoreCase = true) }
        ?: candidateOutbounds.firstOrNull()
        ?: return false
    return selectedOutbound.withoutTag() == candidateOutbound.withoutTag()
}

internal fun ServerConfig.maskedBalancerOutboundAddress(outboundTag: String): String? = rawProxyOutbounds()
    .firstOrNull { it.string("tag") == outboundTag }
    ?.findFirstString("address")
    ?.maskIpAddress()

private fun ServerConfig.rawProxyOutbounds(): List<JsonObject> = rawConfigRoot()
    ?.get("outbounds")
    ?.let { it as? JsonArray }
    .orEmpty()
    .mapNotNull { it as? JsonObject }
    .filter { outbound -> outbound.string("protocol")?.lowercase() !in SPECIAL_OUTBOUND_PROTOCOLS }

private fun ServerConfig.rawConfigRoot(): JsonObject? {
    if (rawConfigJson.isBlank()) return null
    return runCatching { Json.parseToJsonElement(rawConfigJson) as? JsonObject }.getOrNull()
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonElement.findFirstString(key: String): String? = when (this) {
    is JsonObject -> string(key) ?: values.firstNotNullOfOrNull { it.findFirstString(key) }
    is JsonArray -> firstNotNullOfOrNull { it.findFirstString(key) }
    else -> null
}

private fun JsonObject.withoutTag(): JsonObject = JsonObject(this - "tag")

private fun String.maskIpAddress(): String {
    val normalized = trim().removeSurrounding("[", "]")
    normalized.maskIpv4OrNull()?.let { return it }
    if (':' !in normalized) return normalized

    val bytes = runCatching { InetAddress.getByName(normalized).address }.getOrNull() ?: return normalized
    if (bytes.size == IPV4_BYTE_COUNT) {
        return bytes.joinToString(".") { (it.toInt() and 0xff).toString() }.maskIpv4OrNull() ?: normalized
    }
    if (bytes.size != IPV6_BYTE_COUNT) return normalized

    val groups = bytes.asList().chunked(2).map { group ->
        ((group[0].toInt() and 0xff) shl 8) or (group[1].toInt() and 0xff)
    }
    return buildList {
        add(groups.first().toString(16))
        repeat(6) { add("****") }
        add(groups.last().toString(16))
    }.joinToString(":")
}

private fun String.maskIpv4OrNull(): String? {
    val octets = split('.')
    if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
    return "${octets.first()}.**.**.${octets.last()}"
}

private val SPECIAL_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")
private const val IPV4_BYTE_COUNT = 4
private const val IPV6_BYTE_COUNT = 16
