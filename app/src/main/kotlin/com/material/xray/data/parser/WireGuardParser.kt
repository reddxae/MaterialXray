package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_ADDRESS
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_ALLOWED_IPS
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_KEEP_ALIVE
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_MTU
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_PRESHARED_KEY
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_PUBLIC_KEY
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_RESERVED
import com.material.xray.model.ServerConfig
import java.net.URI

internal object WireGuardParser {
    private val schemes = setOf("wireguard", "wg")

    fun parse(uri: String): ServerConfig? = runCatching {
        val parsed = URI(uri)
        if (parsed.scheme?.lowercase() !in schemes) return null
        val secretKey = parsed.rawUserInfo?.let(::decodeUriComponentLeniently)?.takeIf { it.isNotBlank() } ?: return null
        val host = parsed.host?.trim('[', ']') ?: return null
        val port = parsed.port.takeIfValidPort() ?: return null
        val params = parseQuery(parsed.rawQuery.orEmpty())
        val publicKey = firstParam(params, "publickey", "publicKey", "public_key", "peerPublicKey")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val mtu = params["mtu"]?.toIntOrNull()
        if (params.containsKey("mtu") && (mtu == null || mtu <= 0)) return null
        val keepAlive = firstParam(params, "keepalive", "persistentkeepalive", "persistent_keepalive")?.toIntOrNull()
        if (keepAlive != null && keepAlive < 0) return null
        val reserved = params["reserved"]?.takeIf { it.isNotBlank() }
        if (reserved != null && !reserved.isValidReservedBytes()) return null

        ServerConfig(
            protocol = Protocol.WIREGUARD,
            name = parsed.rawFragment?.let(::decodeUriComponentLeniently).orEmpty(),
            address = host,
            port = port,
            password = secretKey,
            extra = buildMap {
                put(SERVER_EXTRA_WIREGUARD_PUBLIC_KEY, publicKey)
                put(SERVER_EXTRA_WIREGUARD_ADDRESS, params["address"]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADDRESS)
                firstParam(params, "presharedkey", "preshared_key", "pre-shared-key", "psk")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(SERVER_EXTRA_WIREGUARD_PRESHARED_KEY, it) }
                mtu?.let { put(SERVER_EXTRA_WIREGUARD_MTU, it.toString()) }
                reserved?.let { put(SERVER_EXTRA_WIREGUARD_RESERVED, it) }
                keepAlive?.takeIf { it > 0 }?.let { put(SERVER_EXTRA_WIREGUARD_KEEP_ALIVE, it.toString()) }
                firstParam(params, "allowedips", "allowed_ips")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(SERVER_EXTRA_WIREGUARD_ALLOWED_IPS, it) }
            },
            rawUri = uri,
        )
    }.getOrNull()

    private fun firstParam(params: Map<String, String>, vararg keys: String): String? = keys.firstNotNullOfOrNull { params[it] }

    private fun String.isValidReservedBytes(): Boolean {
        val bytes = split(',').map { it.trim().toIntOrNull() }
        return bytes.size == 3 && bytes.all { it in 0..255 }
    }

    private const val DEFAULT_ADDRESS = "172.16.0.2/32"
}
