package com.materialxray.model

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
