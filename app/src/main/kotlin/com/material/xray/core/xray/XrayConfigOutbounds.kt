package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val SPECIAL_OUTBOUND_PROTOCOLS = setOf("freedom", "dns", "blackhole")

internal fun buildTunInbound(tunName: String, tag: String) = buildJsonObject {
    put("port", 0)
    put("protocol", "tun")
    put("settings", buildJsonObject {
        put("name", tunName)
        put("MTU", 1500)
    })
    put("sniffing", buildJsonObject {
        put("enabled", true)
        put("routeOnly", true)
        put("destOverride", buildJsonArray {
            add("http")
            add("tls")
            add("quic")
        })
    })
    put("tag", tag)
}

internal fun buildProxyOutbound(
    server: ServerConfig,
    fwmark: Int,
    physicalInterface: String?,
    tag: String,
): JsonObject {
    if (server.rawConfigJson.isNotBlank()) {
        return buildRawProxyOutbound(server.rawConfigJson, fwmark, physicalInterface, tag)
    }

    return buildJsonObject {
        put("tag", tag)
        put("protocol", server.protocol.scheme)
        put("settings", buildOutboundSettings(server))
        put("streamSettings", buildStreamSettings(server, fwmark, physicalInterface))
    }
}

internal fun buildDirectOutbound(fwmark: Int, physicalInterface: String?) = buildJsonObject {
    put("tag", "direct")
    put("protocol", "freedom")
    put("settings", buildJsonObject {})
    put("streamSettings", buildJsonObject { put("sockopt", buildSockopt(fwmark, physicalInterface)) })
}

internal fun buildDnsOutbound(fwmark: Int, physicalInterface: String?) = buildJsonObject {
    put("tag", "dns-out")
    put("protocol", "dns")
    put("settings", buildJsonObject {})
    put("streamSettings", buildJsonObject { put("sockopt", buildSockopt(fwmark, physicalInterface)) })
}

internal fun buildBlockOutbound() = buildJsonObject {
    put("tag", "block")
    put("protocol", "blackhole")
}

internal fun buildCoreOutbounds(
    defaultOutbound: XrayOutbound,
    proxyOutbound: JsonObject,
    directOutbound: JsonObject,
    dnsOutbound: JsonObject,
    blockOutbound: JsonObject,
    appProxyOutbounds: List<JsonObject>,
): List<JsonObject> {
    val coreOutbounds = mapOf(
        XrayOutbound.Proxy to proxyOutbound,
        XrayOutbound.Direct to directOutbound,
        XrayOutbound.Block to blockOutbound,
    )
    return buildList {
        add(coreOutbounds.getValue(defaultOutbound))
        appProxyOutbounds.forEach(::add)
        XrayOutbound.entries
            .filterNot { it == defaultOutbound }
            .forEach { add(coreOutbounds.getValue(it)) }
        add(dnsOutbound)
    }
}

internal fun buildSockopt(fwmark: Int, physicalInterface: String?) = buildJsonObject {
    put("mark", fwmark)
    put("domainStrategy", "UseIP")
    if (!physicalInterface.isNullOrBlank()) {
        put("interface", physicalInterface)
    }
}

private fun buildRawProxyOutbound(
    rawJson: String,
    fwmark: Int,
    physicalInterface: String?,
    tag: String,
): JsonObject {
    val rawObject = Json.parseToJsonElement(rawJson).jsonObject
    val outbounds = rawObject["outbounds"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
    val candidate = outbounds.firstOrNull { outbound ->
        outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
    } ?: outbounds.firstOrNull { outbound ->
        val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase()
        protocol !in SPECIAL_OUTBOUND_PROTOCOLS
    } ?: error("Raw JSON config has no proxy outbound")

    val outbound = candidate.toMutableMap()
    val stream = (outbound["streamSettings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    stream["sockopt"] = buildSockopt(fwmark, physicalInterface)
    outbound["tag"] = JsonPrimitive(tag)
    outbound["streamSettings"] = JsonObject(stream)
    return JsonObject(outbound)
}

private fun buildOutboundSettings(server: ServerConfig): JsonObject = when (server.protocol) {
    Protocol.VLESS -> buildJsonObject {
        put("vnext", buildJsonArray {
            add(buildJsonObject {
                put("address", server.address)
                put("port", server.port)
                put("users", buildJsonArray {
                    add(buildJsonObject {
                        put("id", server.password)
                        put("encryption", server.extra["encryption"] ?: "none")
                        server.extra["flow"]?.let { put("flow", it) }
                    })
                })
            })
        })
    }

    Protocol.VMESS -> buildJsonObject {
        put("vnext", buildJsonArray {
            add(buildJsonObject {
                put("address", server.address)
                put("port", server.port)
                put("users", buildJsonArray {
                    add(buildJsonObject {
                        put("id", server.password)
                        put("alterId", server.extra["alterId"]?.toIntOrNull() ?: 0)
                        put("security", "auto")
                    })
                })
            })
        })
    }

    Protocol.TROJAN -> buildJsonObject {
        put("servers", buildJsonArray {
            add(buildJsonObject {
                put("address", server.address)
                put("port", server.port)
                put("password", server.password)
            })
        })
    }

    Protocol.SHADOWSOCKS -> buildJsonObject {
        put("servers", buildJsonArray {
            add(buildJsonObject {
                put("address", server.address)
                put("port", server.port)
                put("method", server.extra["method"] ?: "aes-256-gcm")
                put("password", server.password)
            })
        })
    }

    Protocol.RAW -> error("Raw JSON configs must be handled before outbound generation")
}

private fun buildStreamSettings(server: ServerConfig, fwmark: Int, physicalInterface: String?) = buildJsonObject {
    put("network", server.transport.type)
    put("security", server.security.type)
    put("sockopt", buildSockopt(fwmark, physicalInterface))
    putSecuritySettings(server)
    putTransportSettings(server)
}

private fun JsonObjectBuilder.putSecuritySettings(server: ServerConfig) {
    when (server.security.type) {
        "tls" -> put("tlsSettings", buildJsonObject {
            if (server.security.sni.isNotEmpty()) put("serverName", server.security.sni)
            if (server.security.fingerprint.isNotEmpty()) put("fingerprint", server.security.fingerprint)
            if (server.security.alpn.isNotEmpty()) {
                put("alpn", buildJsonArray { server.security.alpn.forEach { add(it) } })
            }
        })

        "reality" -> put("realitySettings", buildJsonObject {
            if (server.security.sni.isNotEmpty()) put("serverName", server.security.sni)
            if (server.security.fingerprint.isNotEmpty()) put("fingerprint", server.security.fingerprint)
            if (server.security.publicKey.isNotEmpty()) put("publicKey", server.security.publicKey)
            if (server.security.shortId.isNotEmpty()) put("shortId", server.security.shortId)
        })
    }
}

private fun JsonObjectBuilder.putTransportSettings(server: ServerConfig) {
    when (server.transport.type) {
        "ws" -> put("wsSettings", buildJsonObject {
            if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
            if (server.transport.host.isNotEmpty()) {
                put("headers", buildJsonObject { put("Host", server.transport.host) })
            }
        })

        "grpc" -> put("grpcSettings", buildJsonObject {
            if (server.transport.serviceName.isNotEmpty()) put("serviceName", server.transport.serviceName)
        })

        "xhttp" -> put("xhttpSettings", buildJsonObject {
            if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
            if (server.transport.host.isNotEmpty()) put("host", server.transport.host)
            if (server.transport.mode.isNotEmpty()) put("mode", server.transport.mode)
        })

        "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
            if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
            if (server.transport.host.isNotEmpty()) put("host", server.transport.host)
        })
    }
}
