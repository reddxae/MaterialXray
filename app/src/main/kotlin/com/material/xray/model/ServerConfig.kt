package com.material.xray.model

import kotlinx.serialization.Serializable

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

fun ServerConfig.endpointSummary(): String = listOf(
    protocol.displayName.lowercase(),
    transport.type.lowercase(),
    security.type.lowercase(),
).joinToString(" • ")

internal const val SERVER_EXTRA_XHTTP_EXTRA = "xhttpExtra"
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
