package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_USERNAME
import com.material.xray.model.ServerConfig
import java.net.URI

internal object HttpProxyParser {
    fun parse(uri: String): ServerConfig? = parseProxyUri(
        uri = uri,
        protocol = Protocol.HTTP,
        schemes = setOf("http", "https"),
    )
}

internal object SocksParser {
    private val schemes = setOf("socks", "socks5", "socks5h")

    fun parse(uri: String): ServerConfig? = runCatching {
        val scheme = uri.substringBefore("://", "").lowercase()
        if (scheme !in schemes) return null
        val payload = uri.substringAfter("://").substringBefore('#').substringBefore('?')
        val fragment = uri.substringAfter('#', "").let(::decodeUriComponentLeniently)
        parsePayload(payload, fragment, uri, credentialsAreDecoded = false)
            ?: decodeLenientBase64ToUtf8(payload)?.let {
                parsePayload(it, fragment, uri, credentialsAreDecoded = true)
            }
    }.getOrNull()

    private fun parsePayload(
        payload: String,
        fragment: String,
        rawUri: String,
        credentialsAreDecoded: Boolean,
    ): ServerConfig? {
        val atIndex = payload.lastIndexOf('@')
        val rawCredentials = payload.takeIf { atIndex >= 0 }?.substring(0, atIndex)
        val endpoint = payload.substring(atIndex + 1)
        val parsedEndpoint = runCatching { URI("socks://$endpoint") }.getOrNull() ?: return null
        val host = parsedEndpoint.host?.trim('[', ']') ?: return null
        val port = parsedEndpoint.port.takeIfValidPort() ?: return null
        val credentials = when {
            rawCredentials == null -> emptyList()
            credentialsAreDecoded -> rawCredentials.split(':', limit = 2)
            ':' in rawCredentials -> rawCredentials.split(':', limit = 2).map(::decodeUriComponentLeniently)
            else -> decodeLenientBase64ToUtf8(decodeUriComponentLeniently(rawCredentials))
                ?.split(':', limit = 2)
                .orEmpty()
        }

        return ServerConfig(
            protocol = Protocol.SOCKS,
            name = fragment,
            address = host,
            port = port,
            password = credentials.getOrElse(1) { "" },
            extra = credentials.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { mapOf(SERVER_EXTRA_USERNAME to it) }
                .orEmpty(),
            rawUri = rawUri,
        )
    }
}

private fun parseProxyUri(
    uri: String,
    protocol: Protocol,
    schemes: Set<String>,
): ServerConfig? = runCatching {
    val parsed = URI(uri)
    val scheme = parsed.scheme?.lowercase()?.takeIf { it in schemes } ?: return null
    val host = parsed.host?.trim('[', ']') ?: return null
    val port = parsed.port.takeIfValidPort() ?: if (scheme == "https") 443 else 80
    val rawCredentials = parsed.rawUserInfo
    val credentials = when {
        rawCredentials == null -> emptyList()
        ':' in rawCredentials -> rawCredentials.split(':', limit = 2).map(::decodeUriComponentLeniently)
        else -> listOf(decodeUriComponentLeniently(rawCredentials))
    }

    ServerConfig(
        protocol = protocol,
        name = parsed.rawFragment?.let(::decodeUriComponentLeniently).orEmpty(),
        address = host,
        port = port,
        password = credentials.getOrElse(1) { "" },
        security = ServerConfig.Security(type = if (scheme == "https") "tls" else "none"),
        extra = credentials.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?.let { mapOf(SERVER_EXTRA_USERNAME to it) }
            .orEmpty(),
        rawUri = uri,
    )
}.getOrNull()
