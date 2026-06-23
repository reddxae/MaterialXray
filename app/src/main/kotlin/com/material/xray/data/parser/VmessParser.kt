package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object VmessParser {

    fun parse(uri: String): ServerConfig? = runCatching {
        val encoded = uri.removePrefix("vmess://")
        val decoded = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        val json = Json.parseToJsonElement(decoded).jsonObject

        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull ?: ""

        val port = str("port").toIntOrNull() ?: return null
        val address = str("add").ifEmpty { return null }
        val net = str("net")
        val tls = str("tls")

        ServerConfig(
            protocol = Protocol.VMESS,
            name = str("ps"),
            address = address,
            port = port,
            password = str("id"),
            transport = ServerConfig.Transport(
                type = net.ifEmpty { "tcp" },
                path = str("path"),
                host = str("host"),
                serviceName = str("serviceName"),
            ),
            security = ServerConfig.Security(
                type = tls.ifEmpty { "none" },
                sni = str("sni"),
                fingerprint = str("fp"),
                alpn = str("alpn").let { if (it.isNotEmpty()) it.split(",") else emptyList() },
            ),
            extra = buildMap {
                str("aid").takeIf { it.isNotEmpty() }?.let { put("alterId", it) }
            },
            rawUri = uri,
        )
    }.getOrNull()
}
