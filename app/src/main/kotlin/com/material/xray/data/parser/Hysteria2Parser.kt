package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_CONGESTION
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_DOWN
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_INSECURE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PACKET_SIZE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_PIN_SHA256
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UP
import com.material.xray.model.ServerConfig
import java.net.URI

object Hysteria2Parser {
    private val schemes = setOf("hysteria2", "hy2")

    fun parse(uri: String): ServerConfig? = runCatching {
        val scheme = uri.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme !in schemes) return null

        val stripped = uri.substringAfter("://")
        val withoutFragment = stripped.substringBefore('#')
        val fragment = stripped.substringAfter('#', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }
            ?.let(::decodeUriComponentLeniently)
            .orEmpty()
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val authority = withoutFragment
            .substringBefore('?')
            .substringBefore('/')
        val params = parseQuery(query)

        val atIndex = authority.lastIndexOf('@')
        val userInfo = if (atIndex >= 0) authority.substring(0, atIndex) else ""
        val hostPort = if (atIndex >= 0) authority.substring(atIndex + 1) else authority
        val endpoint = parseEndpoint(hostPort) ?: return null
        val auth = userInfo.takeIf { it.isNotBlank() }
            ?.let(::decodeUriComponentLeniently)
            ?: params["auth"]
            ?: return null
        if (auth.isBlank()) return null

        val port = endpoint.firstPort ?: 443
        val obfs = params["obfs"]
        val obfsPassword = params["obfs-password"]
            ?: params["obfs_password"]
            ?: params["obfsParam"]

        if (!obfs.isNullOrBlank() && obfsPassword.isNullOrBlank()) return null

        ServerConfig(
            protocol = Protocol.HYSTERIA2,
            name = fragment,
            address = endpoint.host,
            port = port,
            password = auth,
            transport = ServerConfig.Transport(type = "hysteria"),
            security = ServerConfig.Security(
                type = "tls",
                sni = params["sni"] ?: params["peer"] ?: "",
                fingerprint = params["fp"] ?: "",
                alpn = params["alpn"]?.split(',')?.filter { it.isNotBlank() } ?: listOf("h3"),
            ),
            extra = buildMap {
                params["insecure"]?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_INSECURE, it) }
                params["pinSHA256"]?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_PIN_SHA256, it) }
                obfs?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_OBFS, it) }
                obfsPassword?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD, it) }
                params["obfs-packet-size"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(SERVER_EXTRA_HYSTERIA_OBFS_PACKET_SIZE, it) }
                bandwidthParam(params, "upmbps", "up_mbps", "up")?.let { put(SERVER_EXTRA_HYSTERIA_UP, it) }
                bandwidthParam(params, "downmbps", "down_mbps", "down")?.let { put(SERVER_EXTRA_HYSTERIA_DOWN, it) }
                params["congestion"]?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_CONGESTION, it) }
                endpoint.hopPorts?.let { put(SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS, it) }
                params["mport"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS, it) }
                firstParam(params, "mportHopInt", "hopInterval", "hop_interval", "udpHopInterval")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL, it) }
                params["udpIdleTimeout"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT, it) }
            },
            rawUri = uri,
        )
    }.getOrNull()

    private fun bandwidthParam(params: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            val value = params[key]?.takeIf { it.isNotBlank() } ?: continue
            return if (value.isLikelyMbpsValue(key)) {
                "$value mbps"
            } else {
                value
            }
        }
        return null
    }

    private fun firstParam(params: Map<String, String>, vararg keys: String): String? = keys.firstNotNullOfOrNull { params[it] }

    private fun String.isLikelyMbpsValue(key: String): Boolean {
        if (!all(Char::isDigit)) return false
        if (key.contains("mbps", ignoreCase = true)) return true
        return key in setOf("up", "down") && (toLongOrNull() ?: Long.MAX_VALUE) < MIN_BPS_BANDWIDTH
    }

    private fun parseEndpoint(value: String): Endpoint? {
        if (value.isBlank()) return null
        if (value.startsWith("[")) {
            val endBracket = value.indexOf(']')
            if (endBracket <= 1) return null
            val rest = value.substring(endBracket + 1)
            if (rest.isNotEmpty() && !rest.startsWith(':')) return null
            return endpoint(value.substring(1, endBracket), rest.removePrefix(":"))
        }

        val lastColon = value.lastIndexOf(':')
        if (lastColon < 0) return endpoint(value, "")
        if (value.indexOf(':') != lastColon) {
            // RFC 3986 requires brackets around an IPv6 host, so a bare multi-colon value cannot
            // carry a port: the last group would be indistinguishable from one. Accept it as a
            // host-only IPv6 literal, and reject anything that is not one instead of storing
            // garbage as a hostname.
            return if (isIpv6Literal(value)) endpoint(value, "") else null
        }
        return endpoint(value.substring(0, lastColon), value.substring(lastColon + 1))
    }

    private fun endpoint(host: String, portSpec: String): Endpoint? {
        val normalizedHost = decodeUriComponentLeniently(host).trim().ifBlank { return null }
        val normalizedPortSpec = portSpec.trim()
        if (normalizedPortSpec.isEmpty()) return Endpoint(normalizedHost, firstPort = null, hopPorts = null)

        val firstPort = normalizedPortSpec
            .substringBefore(',')
            .substringBefore('-')
            .toValidPortOrNull()
            ?: return null
        val hopPorts = normalizedPortSpec.takeIf { ',' in it || '-' in it }
        return Endpoint(normalizedHost, firstPort, hopPorts)
    }

    private fun isIpv6Literal(value: String): Boolean {
        if (value.any { !it.isIpv6LiteralChar() }) return false
        return runCatching { URI("http://[$value]").host != null }.getOrDefault(false)
    }

    private fun Char.isIpv6LiteralChar(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F' || this == ':' || this == '.'

    private data class Endpoint(
        val host: String,
        val firstPort: Int?,
        val hopPorts: String?,
    )

    private const val MIN_BPS_BANDWIDTH = 65_535L
}
