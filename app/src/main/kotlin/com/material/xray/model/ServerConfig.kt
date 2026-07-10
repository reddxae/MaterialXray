package com.material.xray.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ServerConfig(
    val protocol: Protocol,
    val name: String,
    val address: String,
    val port: Int,
    val password: String,
    val transport: Transport = Transport(),
    val security: Security = Security(),
    val extra: Map<String, String> = emptyMap(),
    val rawUri: String = "",
    val rawConfigJson: String = "",
) {
    @Serializable
    data class Transport(
        val type: String = "tcp",
        val path: String = "",
        val host: String = "",
        val serviceName: String = "",
        val mode: String = "",
    )

    @Serializable
    data class Security(
        val type: String = "none",
        val sni: String = "",
        val fingerprint: String = "",
        val alpn: List<String> = emptyList(),
        val publicKey: String = "",
        val shortId: String = "",
    )
}

fun ServerConfig.endpointSummary(): String {
    val proxyOutboundCount = extra[SERVER_EXTRA_PROXY_OUTBOUND_COUNT]?.toIntOrNull()
        ?: rawConfigJson.proxyOutboundCountOrNull()
    if (proxyOutboundCount != null && proxyOutboundCount > 1) {
        return "multiconnect${PROXY_CONFIG_SEPARATOR}$proxyOutboundCount outbounds"
    }

    return buildList {
        add(
            formatProxyConfigSummary(
                ProxyConfigDisplay(
                    protocol = displayProtocolName(),
                    innerEncryption = displayVlessEncryptionMethod(),
                    security = security.type,
                    pqAlgorithm = displayPqAlgorithm(),
                    transport = transport.type.normalizedXrayTransportType(),
                ),
            ),
        )
        if (rawConfigJson.isNotBlank()) add("raw")
    }.joinToString(PROXY_CONFIG_SEPARATOR)
}

internal data class ProxyConfigDisplay(
    val protocol: String,
    val innerEncryption: String? = null,
    val security: String? = null,
    val pqAlgorithm: String? = null,
    val transport: String? = null,
)

internal fun formatProxyConfigSummary(config: ProxyConfigDisplay): String = buildList {
    config.protocol.normalizedDisplayPart()?.let(::add)
    formatSecurityBlock(config)?.let(::add)
    config.transport.normalizedDisplayPart()?.let(::add)
}.joinToString(PROXY_CONFIG_SEPARATOR)

internal fun String.normalizedXrayTransportType(): String {
    val transport = trim()
    return if (transport.equals("tcp", ignoreCase = true)) "raw" else transport.lowercase()
}

private fun ServerConfig.displayProtocolName(): String {
    val encryption = extra["encryption"]?.trim().orEmpty()
    return if (protocol == Protocol.VLESS && encryption.isNotEmpty() && !encryption.equals("none", ignoreCase = true)) {
        "vlessenc"
    } else {
        protocol.displayName.lowercase()
    }
}

private fun formatSecurityBlock(config: ProxyConfigDisplay): String? {
    val security = config.security.normalizedDisplayPart()?.takeUnlessNone()
    if (security != null) {
        return listOfNotNull(config.pqAlgorithm.normalizedDisplayPart(), security).joinToString("+")
    }
    return config.innerEncryption.normalizedDisplayPart()?.takeUnlessNone()
}

private fun ServerConfig.displayVlessEncryptionMethod(): String? {
    if (!isEncryptedVless()) return null
    val method = encryptionValue().split('.').getOrNull(1)?.trim()?.lowercase()
    return method?.takeIf { it in VLESS_ENCRYPTION_METHODS } ?: "native"
}

private fun ServerConfig.displayPqAlgorithm(): String? = extra[SERVER_EXTRA_PQ_ALGORITHM]?.takeIf { it.isNotBlank() }
    ?: extra[SERVER_EXTRA_MLDSA65_VERIFY]?.takeIf { it.isNotBlank() }?.let { "ml-dsa" }

private fun ServerConfig.isEncryptedVless(): Boolean = protocol == Protocol.VLESS && encryptionValue().let { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }

private fun ServerConfig.encryptionValue(): String = extra["encryption"]?.trim().orEmpty()

private fun String.proxyOutboundCountOrNull(): Int? {
    if (isBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(this) as? JsonObject ?: return@runCatching null
        val outbounds = root["outbounds"] as? JsonArray ?: return@runCatching null
        outbounds.count { element ->
            val outbound = element as? JsonObject ?: return@count false
            val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            protocol !in SPECIAL_OUTBOUND_PROTOCOLS
        }
    }.getOrNull()
}

private fun String?.normalizedDisplayPart(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun String.takeUnlessNone(): String? = takeUnless { equals("none", ignoreCase = true) }

private val VLESS_ENCRYPTION_METHODS = setOf("native", "xorpub", "random")
private const val PROXY_CONFIG_SEPARATOR = " • "

internal const val SERVER_EXTRA_XHTTP_EXTRA = "xhttpExtra"
internal const val SERVER_EXTRA_PROXY_OUTBOUND_COUNT = "proxyOutboundCount"
internal const val SERVER_EXTRA_PQ_ALGORITHM = "pqAlgorithm"
internal const val SERVER_EXTRA_MLDSA65_VERIFY = "mldsa65Verify"
internal const val SERVER_EXTRA_SPIDER_X = "spiderX"
internal const val SERVER_EXTRA_HYSTERIA_INSECURE = "hysteriaInsecure"
internal const val SERVER_EXTRA_HYSTERIA_PIN_SHA256 = "hysteriaPinSha256"
internal const val SERVER_EXTRA_HYSTERIA_OBFS = "hysteriaObfs"
internal const val SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD = "hysteriaObfsPassword"
internal const val SERVER_EXTRA_HYSTERIA_OBFS_PACKET_SIZE = "hysteriaObfsPacketSize"
internal const val SERVER_EXTRA_HYSTERIA_UP = "hysteriaUp"
internal const val SERVER_EXTRA_HYSTERIA_DOWN = "hysteriaDown"
internal const val SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS = "hysteriaUdpHopPorts"
internal const val SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL = "hysteriaUdpHopInterval"
internal const val SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT = "hysteriaUdpIdleTimeout"
internal const val SERVER_EXTRA_HYSTERIA_CONGESTION = "hysteriaCongestion"

private val SPECIAL_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns")
