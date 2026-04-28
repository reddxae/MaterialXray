package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.*

class ConfigGenerator {
    private val json = Json { prettyPrint = true }

    data class AppProxyRoute(
        val inboundTag: String,
        val tunName: String,
        val outboundTag: String,
        val server: ServerConfig,
    )

    fun generate(
        server: ServerConfig,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,8.8.8.8",
        logLevel: XrayLogLevel = XrayLogLevel.default,
        defaultOutbound: XrayOutbound = XrayOutbound.default,
        bypassLan: Boolean = true,
        routingRules: List<RoutingRule> = emptyList(),
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
    ): String {
        if (server.rawConfigJson.isNotBlank()) {
            return injectTunIntoRawConfig(
                rawJson = server.rawConfigJson,
                tunName = tunName,
                fwmark = fwmark,
                dnsServers = dnsServers,
                logLevel = logLevel,
                defaultOutbound = defaultOutbound,
                bypassLan = bypassLan,
                routingRules = routingRules,
                appProxyRoutes = appProxyRoutes,
                physicalInterface = physicalInterface,
            )
        }

        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("access", "none")
                put("loglevel", logLevel.value)
            })
            put("dns", buildDns(dnsServers))
            put("inbounds", buildJsonArray {
                add(buildTunInbound(tunName, "tun-in"))
                appProxyRoutes.forEach { route ->
                    add(buildTunInbound(route.tunName, route.inboundTag))
                }
            })
            put("outbounds", buildJsonArray {
                buildCoreOutbounds(
                    defaultOutbound = defaultOutbound,
                    proxyOutbound = buildProxyOutbound(server, fwmark, physicalInterface, tag = "proxy"),
                    directOutbound = buildDirectOutbound(fwmark, physicalInterface),
                    dnsOutbound = buildDnsOutbound(fwmark, physicalInterface),
                    blockOutbound = buildBlockOutbound(),
                    appProxyOutbounds = appProxyRoutes.map { route ->
                        buildProxyOutbound(route.server, fwmark, physicalInterface, tag = route.outboundTag)
                    },
                ).forEach { add(it) }
            })
            put("routing", buildRouting(routingRules, appProxyRoutes, bypassLan))
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    fun injectTunIntoRawConfig(
        rawJson: String,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,8.8.8.8",
        logLevel: XrayLogLevel = XrayLogLevel.default,
        defaultOutbound: XrayOutbound = XrayOutbound.default,
        bypassLan: Boolean = true,
        routingRules: List<RoutingRule> = emptyList(),
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
    ): String {
        val original = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()

        val existingInbounds = original["inbounds"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val hasTunInbound = existingInbounds.any { inbound ->
            val inboundObject = inbound as? JsonObject ?: return@any false
            inboundObject["protocol"]?.jsonPrimitive?.contentOrNull?.equals("tun", ignoreCase = true) == true
        }
        if (!hasTunInbound) {
            existingInbounds.add(0, buildTunInbound(tunName, "tun-in"))
        }
        appProxyRoutes.forEach { route ->
            val hasAppInbound = existingInbounds.any { inbound ->
                val inboundObject = inbound as? JsonObject ?: return@any false
                inboundObject["tag"]?.jsonPrimitive?.contentOrNull.equals(route.inboundTag, ignoreCase = true)
            }
            if (!hasAppInbound) {
                existingInbounds.add(buildTunInbound(route.tunName, route.inboundTag))
            }
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
                    ?.lowercase() !in SPECIAL_OUTBOUND_PROTOCOLS
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

        val proxyOutbound = normalizedOutbounds.firstOrNull { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
        } ?: error("Raw JSON config has no proxy outbound")
        val directOutbound = buildDirectOutbound(fwmark, physicalInterface)
        val dnsOutbound = buildDnsOutbound(fwmark, physicalInterface)
        val blockOutbound = buildBlockOutbound()
        val appProxyOutbounds = appProxyRoutes.map { route ->
            buildProxyOutbound(route.server, fwmark, physicalInterface, route.outboundTag)
        }

        val managedTags = buildSet {
            add("proxy")
            add("direct")
            add("dns-out")
            add("block")
            appProxyRoutes.forEach { add(it.outboundTag) }
        }
        val unmanagedOutbounds = normalizedOutbounds.filterNot { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull?.let { it in managedTags } == true
        }
        original["outbounds"] = JsonArray(
            buildCoreOutbounds(
                defaultOutbound = defaultOutbound,
                proxyOutbound = proxyOutbound,
                directOutbound = directOutbound,
                dnsOutbound = dnsOutbound,
                blockOutbound = blockOutbound,
                appProxyOutbounds = appProxyOutbounds,
            ) + unmanagedOutbounds
        )
        original["log"] = buildJsonObject {
            put("access", "none")
            put("loglevel", logLevel.value)
        }
        original["dns"] = buildDns(dnsServers)
        original["routing"] = buildRouting(routingRules, appProxyRoutes, bypassLan)

        return json.encodeToString(JsonObject.serializer(), JsonObject(original))
    }

    private fun buildTunInbound(tunName: String, tag: String) = buildJsonObject {
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

    private fun buildProxyOutbound(
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

    private fun buildCoreOutbounds(
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

    private fun buildRouting(
        routingRules: List<RoutingRule>,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        bypassLan: Boolean = true,
    ) = buildJsonObject {
        put("domainStrategy", "IPOnDemand")
        put("rules", buildJsonArray {
            add(buildJsonObject {
                put("type", "field")
                put("inboundTag", buildJsonArray {
                    add("tun-in")
                    appProxyRoutes.forEach { add(it.inboundTag) }
                })
                put("port", "53")
                put("outboundTag", "dns-out")
            })
            appProxyRoutes.forEach { route ->
                add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", buildJsonArray { add(route.inboundTag) })
                    put("outboundTag", route.outboundTag)
                })
            }
            if (bypassLan) {
                add(buildJsonObject {
                    put("type", "field")
                    put("ip", buildJsonArray { add("geoip:private") })
                    put("outboundTag", "direct")
                })
                add(buildJsonObject {
                    put("type", "field")
                    put("domain", buildJsonArray { add("geosite:private") })
                    put("outboundTag", "direct")
                })
            }
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

    private companion object {
        val SPECIAL_OUTBOUND_PROTOCOLS = setOf("freedom", "dns", "blackhole")
    }
}
