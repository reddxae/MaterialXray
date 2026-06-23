package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
enum class Protocol(
    val displayName: String,
    val scheme: String,
    private val aliases: Set<String> = emptySet(),
) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    HYSTERIA2("Hysteria2", "hysteria", setOf("hysteria2", "hy2")),
    RAW("Raw JSON", "raw"),
    ;

    companion object {
        fun fromScheme(scheme: String): Protocol? {
            val normalized = scheme.lowercase()
            return entries.find { it.scheme == normalized || normalized in it.aliases }
        }
    }
}
