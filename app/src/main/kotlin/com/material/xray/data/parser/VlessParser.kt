package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import com.material.xray.model.SERVER_EXTRA_MLDSA65_VERIFY
import com.material.xray.model.SERVER_EXTRA_SPIDER_X
import com.material.xray.model.SERVER_EXTRA_XHTTP_EXTRA
import java.net.URI
import java.net.URLDecoder

object VlessParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val parsed = URI(uri)
        val userInfo = parsed.rawUserInfo ?: return null
        val host = parsed.host ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val fragment = parsed.rawFragment?.let { decodeUriComponent(it) } ?: ""
        val params = parseQuery(parsed.rawQuery ?: "")

        ServerConfig(
            protocol = Protocol.VLESS,
            name = fragment,
            address = host,
            port = port,
            password = userInfo,
            transport = ServerConfig.Transport(
                type = params["type"] ?: "tcp",
                path = params["path"]?.let { decodeUriComponent(it) } ?: "",
                host = params["host"] ?: "",
                serviceName = params["serviceName"] ?: "",
                mode = params["mode"] ?: "",
            ),
            security = ServerConfig.Security(
                type = params["security"] ?: "none",
                sni = params["sni"] ?: "",
                fingerprint = params["fp"] ?: "",
                alpn = params["alpn"]?.split(",") ?: emptyList(),
                publicKey = params["pbk"] ?: "",
                shortId = params["sid"] ?: "",
            ),
            extra = buildMap {
                params["encryption"]?.let { put("encryption", it) }
                params["flow"]?.let { put("flow", it) }
                params["extra"]?.let { put(SERVER_EXTRA_XHTTP_EXTRA, decodeUriComponent(it)) }
                params["pqv"]?.let { put(SERVER_EXTRA_MLDSA65_VERIFY, decodeUriComponent(it)) }
                params["spx"]?.let { put(SERVER_EXTRA_SPIDER_X, decodeUriComponent(it)) }
            },
            rawUri = uri,
        )
    }.getOrNull()

    private fun decodeUriComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
