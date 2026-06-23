package com.material.xray.data.parser

import com.material.xray.model.ServerConfig

class ShareLinkParser {

    fun parse(uri: String): ServerConfig? {
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("vless://") -> VlessParser.parse(trimmed)
            trimmed.startsWith("vmess://") -> VmessParser.parse(trimmed)
            trimmed.startsWith("trojan://") -> TrojanParser.parse(trimmed)
            trimmed.startsWith("ss://") -> ShadowsocksParser.parse(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> Hysteria2Parser.parse(trimmed)
            else -> null
        }
    }

    fun parseMultiple(text: String): List<ServerConfig> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { parse(it) }
}
