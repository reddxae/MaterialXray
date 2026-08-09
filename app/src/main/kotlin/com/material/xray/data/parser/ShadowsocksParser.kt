package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import java.net.URI

object ShadowsocksParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val stripped = uri.removePrefix("ss://")
        val fragmentIdx = stripped.indexOf('#')
        val name = if (fragmentIdx >= 0) {
            decodeUriComponentLeniently(stripped.substring(fragmentIdx + 1))
        } else {
            ""
        }
        val main = if (fragmentIdx >= 0) stripped.substring(0, fragmentIdx) else stripped

        val atIdx = main.lastIndexOf('@')
        if (atIdx < 0) return parseLegacy(main, name, uri)

        val userInfo = main.substring(0, atIdx)
        val hostPort = main.substring(atIdx + 1)

        val decoded = decodeLenientBase64ToUtf8(userInfo) ?: return null

        val colonIdx = decoded.indexOf(':')
        if (colonIdx < 0) return null
        val method = decoded.substring(0, colonIdx)
        val password = decoded.substring(colonIdx + 1)

        val parsed = URI("ss://x@$hostPort")
        val host = parsed.host ?: return null
        val port = parsed.port.takeIfValidPort() ?: return null

        ServerConfig(
            protocol = Protocol.SHADOWSOCKS,
            name = name,
            address = host,
            port = port,
            password = password,
            extra = mapOf("method" to method),
            rawUri = uri,
        )
    }.getOrNull()

    private fun parseLegacy(encoded: String, name: String, rawUri: String): ServerConfig? = runCatching {
        val decoded = decodeLenientBase64ToUtf8(encoded) ?: return null
        val atIdx = decoded.lastIndexOf('@')
        if (atIdx < 0) return null
        val methodPassword = decoded.substring(0, atIdx)
        val hostPort = decoded.substring(atIdx + 1)
        val colonIdx = methodPassword.indexOf(':')
        if (colonIdx < 0) return null
        val method = methodPassword.substring(0, colonIdx)
        val password = methodPassword.substring(colonIdx + 1)
        val parsed = URI("ss://x@$hostPort")

        ServerConfig(
            protocol = Protocol.SHADOWSOCKS,
            name = name,
            address = parsed.host ?: return null,
            port = parsed.port.takeIfValidPort() ?: return null,
            password = password,
            extra = mapOf("method" to method),
            rawUri = rawUri,
        )
    }.getOrNull()
}
