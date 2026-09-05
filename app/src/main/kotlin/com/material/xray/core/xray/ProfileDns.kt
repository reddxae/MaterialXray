package com.material.xray.core.xray

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Keep proxy endpoint lookups independent of DNS reached through the not-yet-ready proxy. */
internal fun JsonObject.withBootstrapDnsHosts(bootstrapHosts: Map<String, List<String>>): JsonObject {
    val providerHosts = (get("hosts") as? JsonObject).orEmpty()
    val missingHosts = bootstrapHosts.filterKeys { host ->
        providerHosts.keys.none { it.mayMatchDnsHost(host) }
    }
    if (missingHosts.isEmpty()) return this
    val hosts = missingHosts.mapValues { (_, addresses) -> JsonArray(addresses.distinct().map(::JsonPrimitive)) } + providerHosts
    return JsonObject(this + ("hosts" to JsonObject(hosts)))
}

private fun String.mayMatchDnsHost(host: String): Boolean {
    val normalizedHost = host.lowercase(Locale.ROOT)
    val pattern = substringAfter(':').lowercase(Locale.ROOT)
    return when {
        ':' !in this -> equals(host, ignoreCase = true)
        startsWith("full:") -> pattern == normalizedHost
        startsWith("domain:") -> pattern == normalizedHost || normalizedHost.endsWith(".$pattern")
        startsWith("keyword:") -> pattern in normalizedHost
        startsWith("regexp:") -> runCatching { Regex(substringAfter(':')).containsMatchIn(normalizedHost) }.getOrDefault(true)
        // External/geosite rules require Xray's data files. Preserve them conservatively instead
        // of mixing system IPs into a provider mapping or overriding a domain alias.
        else -> true
    }
}
