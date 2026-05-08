package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class RawConfigTunInjector(
    private val json: Json,
) {
    fun inject(
        rawJson: String,
        tunName: String,
        fwmark: Int,
        dnsServers: String,
        domesticDnsServers: String,
        logLevel: XrayLogLevel,
        defaultOutbound: XrayOutbound,
        bypassLan: Boolean,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule>,
        appProxyRoutes: List<AppProxyRoute>,
        physicalInterface: String?,
    ): String {
        val original = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()
        original["inbounds"] = injectTunInbounds(original["inbounds"] as? JsonArray, tunName, appProxyRoutes)

        val normalizedOutbounds = normalizeOutbounds(original["outbounds"] as? JsonArray, fwmark, physicalInterface, allowIpv6)
        val proxyOutbound = normalizedOutbounds.firstOrNull { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
        } ?: error("Raw JSON config has no proxy outbound")

        val appProxyOutbounds = appProxyRoutes.filterNot { it.applyRoutingRules }.map { route ->
            buildProxyOutbound(route.server, fwmark, physicalInterface, route.outboundTag, allowIpv6)
        }
        val unmanagedOutbounds = normalizedOutbounds.filterNot { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull?.let { it in managedOutboundTags(appProxyRoutes) } == true
        }

        original["outbounds"] = JsonArray(
            buildCoreOutbounds(
                defaultOutbound = defaultOutbound,
                proxyOutbound = proxyOutbound,
                directOutbound = buildDirectOutbound(fwmark, physicalInterface, allowIpv6),
                dnsOutbound = buildDnsOutbound(fwmark, physicalInterface, allowIpv6),
                blockOutbound = buildBlockOutbound(),
                appProxyOutbounds = appProxyOutbounds,
            ) + unmanagedOutbounds
        )
        original["log"] = buildLogConfig(logLevel)
        original["dns"] = buildDns(dnsServers, domesticDnsServers, routingRules, bypassLan, allowIpv6)
        original["routing"] = buildRouting(routingRules, appProxyRoutes, bypassLan, domesticDnsServers)

        return json.encodeToString(JsonObject.serializer(), JsonObject(original))
    }

    private fun injectTunInbounds(
        inbounds: JsonArray?,
        tunName: String,
        appProxyRoutes: List<AppProxyRoute>,
    ): JsonArray {
        val existingInbounds = inbounds?.toMutableList() ?: mutableListOf()
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
        return JsonArray(existingInbounds)
    }

    private fun normalizeOutbounds(
        outbounds: JsonArray?,
        fwmark: Int,
        physicalInterface: String?,
        allowIpv6: Boolean,
    ): List<JsonObject> {
        val existingOutbounds = outbounds?.mapNotNull { it as? JsonObject }.orEmpty()
        val firstProxyCandidateIndex = firstProxyCandidateIndex(existingOutbounds)
        return existingOutbounds.mapIndexed { index, outbound ->
            val obj = outbound.toMutableMap()
            val stream = (obj["streamSettings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            stream["sockopt"] = buildSockopt(fwmark, physicalInterface, allowIpv6)
            obj["streamSettings"] = JsonObject(stream)
            if (index == firstProxyCandidateIndex) {
                obj["tag"] = JsonPrimitive("proxy")
            }
            JsonObject(obj)
        }
    }

    private fun firstProxyCandidateIndex(outbounds: List<JsonObject>): Int {
        val hasProxyTag = outbounds.any { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
        }
        if (hasProxyTag) return -1

        return outbounds.indexOfFirst { outbound ->
            outbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase() !in SPECIAL_OUTBOUND_PROTOCOLS
        }
    }

    private fun managedOutboundTags(appProxyRoutes: List<AppProxyRoute>) = buildSet {
        add("proxy")
        add("direct")
        add("dns-out")
        add("block")
        appProxyRoutes.filterNot { it.applyRoutingRules }.forEach { add(it.outboundTag) }
    }
}
