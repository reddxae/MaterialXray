package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ConfigGenerator {
    private val json = Json { prettyPrint = true }

    fun generate(
        server: ServerConfig,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,1.0.0.1",
        domesticDnsServers: String = "",
        logLevel: XrayLogLevel = XrayLogLevel.default,
        defaultOutbound: XrayOutbound = XrayOutbound.default,
        bypassLan: Boolean = true,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule> = emptyList(),
        routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
        routingDomainMatcher: String? = null,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
        xrayApiSocketName: String = XRAY_API_SOCKET_NAME_PREFIX,
    ): String {
        if (server.rawConfigJson.isNotBlank()) {
            return injectTunIntoRawConfig(
                rawJson = server.rawConfigJson,
                tunName = tunName,
                fwmark = fwmark,
                dnsServers = dnsServers,
                domesticDnsServers = domesticDnsServers,
                logLevel = logLevel,
                defaultOutbound = defaultOutbound,
                bypassLan = bypassLan,
                allowIpv6 = allowIpv6,
                routingRules = routingRules,
                routingDomainStrategy = routingDomainStrategy,
                routingDomainMatcher = routingDomainMatcher,
                appProxyRoutes = appProxyRoutes,
                physicalInterface = physicalInterface,
                xrayApiSocketName = xrayApiSocketName,
            )
        }

        val config = buildJsonObject {
            put("log", buildLogConfig(logLevel))
            put("dns", buildDns(dnsServers, domesticDnsServers, routingRules, bypassLan, allowIpv6))
            put(
                "inbounds",
                buildJsonArray {
                    add(buildTunInbound(tunName, "tun-in"))
                    appProxyRoutes.forEach { route ->
                        add(buildTunInbound(route.tunName, route.inboundTag))
                    }
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    buildCoreOutbounds(
                        defaultOutbound = defaultOutbound,
                        proxyOutbound = buildProxyOutbound(server, fwmark, physicalInterface, tag = "proxy", allowIpv6 = allowIpv6),
                        directOutbound = buildDirectOutbound(fwmark, physicalInterface, allowIpv6),
                        dnsOutbound = buildDnsOutbound(fwmark, physicalInterface, allowIpv6),
                        blockOutbound = buildBlockOutbound(),
                        appProxyOutbounds = appProxyRoutes.filterNot { it.applyRoutingRules }.map { route ->
                            buildProxyOutbound(route.server, fwmark, physicalInterface, tag = route.outboundTag, allowIpv6 = allowIpv6)
                        },
                    ).forEach { add(it) }
                },
            )
            put("api", buildStatsApi(xrayApiSocketName))
            put("stats", buildStatsConfig())
            put("policy", buildStatsPolicy())
            put(
                "routing",
                buildRouting(
                    routingRules,
                    appProxyRoutes,
                    bypassLan,
                    domesticDnsServers,
                    routingDomainStrategy,
                    routingDomainMatcher,
                ),
            )
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    fun injectTunIntoRawConfig(
        rawJson: String,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "1.1.1.1,1.0.0.1",
        domesticDnsServers: String = "",
        logLevel: XrayLogLevel = XrayLogLevel.default,
        defaultOutbound: XrayOutbound = XrayOutbound.default,
        bypassLan: Boolean = true,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule> = emptyList(),
        routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
        routingDomainMatcher: String? = null,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
        xrayApiSocketName: String = XRAY_API_SOCKET_NAME_PREFIX,
    ): String = RawConfigTunInjector(json).inject(
        rawJson = rawJson,
        tunName = tunName,
        fwmark = fwmark,
        dnsServers = dnsServers,
        domesticDnsServers = domesticDnsServers,
        logLevel = logLevel,
        defaultOutbound = defaultOutbound,
        bypassLan = bypassLan,
        allowIpv6 = allowIpv6,
        routingRules = routingRules,
        routingDomainStrategy = routingDomainStrategy,
        routingDomainMatcher = routingDomainMatcher,
        appProxyRoutes = appProxyRoutes,
        physicalInterface = physicalInterface,
        xrayApiSocketName = xrayApiSocketName,
    )
}
