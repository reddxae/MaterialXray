package com.materialxray.core.xray

import com.materialxray.model.Protocol
import com.materialxray.model.RoutingRule
import com.materialxray.model.RoutingRuleOperator
import com.materialxray.model.ServerConfig
import com.materialxray.model.XrayLogLevel
import kotlinx.serialization.json.*

class ConfigGenerator {
    private val json = Json { prettyPrint = true }

    fun generate(
        server: ServerConfig,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,8.8.8.8",
        logLevel: XrayLogLevel = XrayLogLevel.default,
        routingRules: List<RoutingRule> = emptyList(),
        physicalInterface: String? = null,
    ): String {
        if (server.rawConfigJson.isNotBlank()) {
            return injectTunIntoRawConfig(
                rawJson = server.rawConfigJson,
                tunName = tunName,
                fwmark = fwmark,
                dnsServers = dnsServers,
                logLevel = logLevel,
                routingRules = routingRules,
                physicalInterface = physicalInterface,
            )
        }

        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("access", "none")
                put("loglevel", logLevel.value)
            })
            put("dns", buildDns(dnsServers))
            put("inbounds", buildJsonArray { add(buildTunInbound(tunName)) })
            put("outbounds", buildJsonArray {
                add(buildProxyOutbound(server, fwmark, physicalInterface))
                add(buildDirectOutbound(fwmark, physicalInterface))
                add(buildDnsOutbound(fwmark, physicalInterface))
                add(buildBlockOutbound())
            })
            put("routing", buildRouting(routingRules))
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    fun injectTunIntoRawConfig(
        rawJson: String,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,8.8.8.8",
        logLevel: XrayLogLevel = XrayLogLevel.default,
        routingRules: List<RoutingRule> = emptyList(),
        physicalInterface: String? = null,
    ): String {
        val original = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()

        val existingInbounds = original["inbounds"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val hasTunInbound = existingInbounds.any { inbound ->
            val inboundObject = inbound as? JsonObject ?: return@any false
            inboundObject["protocol"]?.jsonPrimitive?.contentOrNull?.equals("tun", ignoreCase = true) == true
        }
        if (!hasTunInbound) {
            existingInbounds.add(0, buildTunInbound(tunName))
        }
        original["inbounds"] = JsonArray(existingInbounds)

        val existingOutbounds = original["outbounds"]
            ?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.toMutableList()
            ?: mutableListOf()

        val hasProxyTag = existingOutbounds.any { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
        }
        val firstProxyCandidateIndex = if (hasProxyTag) {
            -1
        } else {
            existingOutbounds.indexOfFirst { outbound ->
                outbound["protocol"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.lowercase() !in setOf("freedom", "dns", "blackhole")
            }
        }

        val normalizedOutbounds = existingOutbounds.mapIndexed { index, outbound ->
            val obj = outbound.toMutableMap()
            val stream = (obj["streamSettings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            stream["sockopt"] = buildSockopt(fwmark, physicalInterface)
            obj["streamSettings"] = JsonObject(stream)

            if (index == firstProxyCandidateIndex) {
                obj["tag"] = JsonPrimitive("proxy")
            }

            JsonObject(obj)
        }.toMutableList()

        fun upsertOutbound(tag: String, outbound: JsonObject) {
            val existingIndex = normalizedOutbounds.indexOfFirst { candidate ->
                candidate["tag"]?.jsonPrimitive?.contentOrNull.equals(tag, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                normalizedOutbounds[existingIndex] = outbound
            } else {
                normalizedOutbounds.add(outbound)
            }
        }

        upsertOutbound("direct", buildDirectOutbound(fwmark, physicalInterface))
        upsertOutbound("dns-out", buildDnsOutbound(fwmark, physicalInterface))
        upsertOutbound("block", buildBlockOutbound())

        original["outbounds"] = JsonArray(normalizedOutbounds)
        original["log"] = buildJsonObject {
            put("access", "none")
            put("loglevel", logLevel.value)
        }
        original["dns"] = buildDns(dnsServers)
        original["routing"] = buildRouting(routingRules)

        return json.encodeToString(JsonObject.serializer(), JsonObject(original))
    }

    private fun buildTunInbound(tunName: String) = buildJsonObject {
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
        put("tag", "tun-in")
    }

    private fun buildProxyOutbound(server: ServerConfig, fwmark: Int, physicalInterface: String?) = buildJsonObject {
        put("tag", "proxy")
        put("protocol", server.protocol.scheme)
        put("settings", buildOutboundSettings(server))
        put("streamSettings", buildStreamSettings(server, fwmark, physicalInterface))
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

        when (server.security.type) {
            "tls" -> put("tlsSettings", buildJsonObject {
                if (server.security.sni.isNotEmpty()) put("serverName", server.security.sni)
                if (server.security.fingerprint.isNotEmpty()) put("fingerprint", server.security.fingerprint)
                if (server.security.alpn.isNotEmpty()) put(
                    "alpn",
                    buildJsonArray { server.security.alpn.forEach { add(it) } })
            })

            "reality" -> put("realitySettings", buildJsonObject {
                if (server.security.sni.isNotEmpty()) put("serverName", server.security.sni)
                if (server.security.fingerprint.isNotEmpty()) put("fingerprint", server.security.fingerprint)
                if (server.security.publicKey.isNotEmpty()) put("publicKey", server.security.publicKey)
                if (server.security.shortId.isNotEmpty()) put("shortId", server.security.shortId)
            })
        }

        when (server.transport.type) {
            "ws" -> put("wsSettings", buildJsonObject {
                if (server.transport.path.isNotEmpty()) put("path", server.transport.path)
                if (server.transport.host.isNotEmpty()) put(
                    "headers",
                    buildJsonObject { put("Host", server.transport.host) })
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

    private fun buildDirectOutbound(fwmark: Int, physicalInterface: String?) = buildJsonObject {
        put("tag", "direct")
        put("protocol", "freedom")
        put("settings", buildJsonObject {})
        put("streamSettings", buildJsonObject { put("sockopt", buildSockopt(fwmark, physicalInterface)) })
    }

    private fun buildDnsOutbound(fwmark: Int, physicalInterface: String?) = buildJsonObject {
        put("tag", "dns-out")
        put("protocol", "dns")
        put("settings", buildJsonObject {})
        put("streamSettings", buildJsonObject { put("sockopt", buildSockopt(fwmark, physicalInterface)) })
    }

    private fun buildBlockOutbound() = buildJsonObject {
        put("tag", "block")
        put("protocol", "blackhole")
    }

    private fun buildSockopt(fwmark: Int, physicalInterface: String?) = buildJsonObject {
        put("mark", fwmark)
        put("domainStrategy", "UseIP")
        if (!physicalInterface.isNullOrBlank()) {
            put("interface", physicalInterface)
        }
    }

    private fun buildDns(servers: String) = buildJsonObject {
        put("servers", buildJsonArray {
            servers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        })
    }

    private fun buildRouting(routingRules: List<RoutingRule>) = buildJsonObject {
        put("domainStrategy", "IPOnDemand")
        put("rules", buildJsonArray {
            add(buildJsonObject {
                put("type", "field")
                put("inboundTag", buildJsonArray { add("tun-in") })
                put("port", "53")
                put("outboundTag", "dns-out")
            })
            routingRules.filter { it.enabled }.forEach { rule ->
                if (rule.operator == RoutingRuleOperator.OR) {
                    buildOrRules(rule).forEach { add(it) }
                } else {
                    add(rule.toXrayRule())
                }
            }
        })
    }

    private fun buildOrRules(rule: RoutingRule): List<JsonObject> {
        fun base(): MutableMap<String, JsonElement> = mutableMapOf(
            "type" to JsonPrimitive("field"),
            "outboundTag" to JsonPrimitive(rule.outboundTag),
        )

        val rules = mutableListOf<JsonObject>()

        if (rule.domains.isNotEmpty()) {
            rules += JsonObject(base().apply {
                put("domain", buildJsonArray { rule.domains.forEach { add(it) } })
            })
        }
        if (rule.ips.isNotEmpty()) {
            rules += JsonObject(base().apply {
                put("ip", buildJsonArray { rule.ips.forEach { add(it) } })
            })
        }
        rule.port?.takeIf { it.isNotBlank() }?.let { port ->
            rules += JsonObject(base().apply {
                put("port", JsonPrimitive(port))
            })
        }
        if (rule.protocols.isNotEmpty()) {
            rules += JsonObject(base().apply {
                put("protocol", buildJsonArray { rule.protocols.forEach { add(it) } })
            })
        }

        if (rules.isEmpty()) {
            rules += JsonObject(base())
        }

        return rules
    }
}
