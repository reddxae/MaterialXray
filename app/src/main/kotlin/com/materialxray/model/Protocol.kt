package com.materialxray.model

import kotlinx.serialization.Serializable

@Serializable
enum class Protocol(val displayName: String, val scheme: String) {
    VLESS("VLESS", "vless"),
    VMESS("VMess", "vmess"),
    TROJAN("Trojan", "trojan"),
    SHADOWSOCKS("Shadowsocks", "ss"),
    RAW("Raw JSON", "raw");

    companion object {
        fun fromScheme(scheme: String): Protocol? =
            entries.find { it.scheme == scheme.lowercase() }
    }
}
