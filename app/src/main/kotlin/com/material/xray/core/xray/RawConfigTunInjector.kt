package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
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
        bootstrapDnsHosts: Map<String, List<String>> = emptyMap(),
        logLevel: XrayLogLevel,
        defaultOutbound: XrayOutbound,
        bypassLan: Boolean,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule>,
        routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
        routingDomainMatcher: String? = null,
        appProxyRoutes: List<AppProxyRoute>,
        physicalInterface: String?,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
        inbounds: List<XrayInbound>? = null,
    ): String {
        val original = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()
        val effectiveInbounds = inbounds ?: buildList {
            add(XrayInbound.Tun(tunName, "tun-in", tunMtu))
            appProxyRoutes.forEach { route -> add(XrayInbound.Tun(route.tunName, route.inboundTag, tunMtu)) }
        }
        original["inbounds"] = JsonArray(effectiveInbounds.map(XrayInbound::toJson))

        val normalizedOutbounds = normalizeOutbounds(original["outbounds"] as? JsonArray, fwmark, physicalInterface, allowIpv6)
        val proxyOutbound = normalizedOutbounds.firstOrNull { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
        } ?: error("Raw JSON config has no proxy outbound")
        val proxyOutboundTag = requireNotNull(proxyOutbound["tag"]?.jsonPrimitive?.contentOrNull)

        val appProxyOutbounds = appProxyRoutes.filterNot { it.applyRoutingRules }.map { route ->
            buildProxyOutbound(route.server, fwmark, physicalInterface, route.outboundTag, allowIpv6)
        }
        val managedOutboundTags = managedOutboundTags(appProxyRoutes)
        val unmanagedOutbounds = normalizedOutbounds.filterNot { outbound ->
            val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull
            tag != null && managedOutboundTags.any { managedTag -> managedTag.equals(tag, ignoreCase = true) }
        }

        original["outbounds"] = JsonArray(
            buildCoreOutbounds(
                defaultOutbound = defaultOutbound,
                proxyOutbound = proxyOutbound,
                directOutbound = buildDirectOutbound(fwmark, physicalInterface, allowIpv6),
                dnsOutbound = buildDnsOutbound(fwmark, physicalInterface, allowIpv6),
                blockOutbound = buildBlockOutbound(),
                appProxyOutbounds = appProxyOutbounds,
            ) + unmanagedOutbounds,
        )
        original["log"] = buildLogConfig(logLevel)
        original["dns"] = buildDns(
            dnsServers,
            domesticDnsServers,
            bootstrapDnsHosts,
            routingRules,
            bypassLan,
            allowIpv6,
        )
        original["api"] = buildStatsApi(
            endpoint = xrayApiEndpoint,
            enableObservatory = original["observatory"] is JsonObject || original["burstObservatory"] is JsonObject,
        )
        original["stats"] = buildStatsConfig()
        original["policy"] = buildStatsPolicy(xrayBufferSizeKiB)
        original["routing"] = mergeRouting(
            generated = buildRouting(
                routingRules = routingRules,
                appProxyRoutes = appProxyRoutes,
                bypassLan = bypassLan,
                dnsServers = dnsServers,
                domesticDnsServers = domesticDnsServers,
                domainStrategy = routingDomainStrategy,
                domainMatcher = routingDomainMatcher,
                defaultDnsOutboundTag = proxyOutboundTag,
                dataInboundTags = effectiveInbounds.map { it.tag },
            ),
            raw = original["routing"] as? JsonObject,
        )

        return json.encodeToString(JsonObject.serializer(), JsonObject(original))
    }

    // The app owns DNS for raw configs: it replaces their `dns` block outright, so the rules that route
    // its injected resolvers stay ahead of the raw rules. `default-dns` only matches Xray's own DNS
    // client, so leading with it cannot capture unrelated traffic, while trailing it would let any raw
    // catch-all divert resolver traffic away from the proxy.
    private fun mergeRouting(generated: JsonObject, raw: JsonObject?): JsonObject {
        if (raw == null) return generated

        val generatedRules = (generated["rules"] as? JsonArray).orEmpty()
        val rawRules = (raw["rules"] as? JsonArray).orEmpty()
        return JsonObject(
            generated.toMutableMap().apply {
                putAll(raw)
                put("rules", JsonArray(generatedRules + rawRules))
            },
        )
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
