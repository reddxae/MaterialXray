package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
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
        routingFallbackOutbound: XrayOutbound? = null,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
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
                routingFallbackOutbound = routingFallbackOutbound,
                appProxyRoutes = appProxyRoutes,
                physicalInterface = physicalInterface,
                xrayApiEndpoint = xrayApiEndpoint,
                xrayBufferSizeKiB = xrayBufferSizeKiB,
                tunMtu = tunMtu,
            )
        }

        val config = buildJsonObject {
            put("log", buildLogConfig(logLevel))
            put("dns", buildDns(dnsServers, domesticDnsServers, routingRules, bypassLan, allowIpv6))
            put(
                "inbounds",
                buildJsonArray {
                    add(buildTunInbound(tunName, "tun-in", tunMtu))
                    appProxyRoutes.forEach { route ->
                        add(buildTunInbound(route.tunName, route.inboundTag, tunMtu))
                    }
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    buildCoreOutbounds(
                        defaultOutbound = routingFallbackOutbound ?: defaultOutbound,
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
            put("api", buildStatsApi(xrayApiEndpoint))
            put("stats", buildStatsConfig())
            put("policy", buildStatsPolicy(xrayBufferSizeKiB))
            put(
                "routing",
                buildRouting(
                    routingRules = routingRules,
                    appProxyRoutes = appProxyRoutes,
                    bypassLan = bypassLan,
                    dnsServers = dnsServers,
                    domesticDnsServers = domesticDnsServers,
                    domainStrategy = routingDomainStrategy,
                    domainMatcher = routingDomainMatcher,
                    allowIpv6 = allowIpv6,
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
        routingFallbackOutbound: XrayOutbound? = null,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
    ): String = RawConfigTunInjector(json).inject(
        rawJson = rawJson,
        tunName = tunName,
        fwmark = fwmark,
        dnsServers = dnsServers,
        domesticDnsServers = domesticDnsServers,
        logLevel = logLevel,
        defaultOutbound = routingFallbackOutbound ?: defaultOutbound,
        bypassLan = bypassLan,
        allowIpv6 = allowIpv6,
        routingRules = routingRules,
        routingDomainStrategy = routingDomainStrategy,
        routingDomainMatcher = routingDomainMatcher,
        appProxyRoutes = appProxyRoutes,
        physicalInterface = physicalInterface,
        xrayApiEndpoint = xrayApiEndpoint,
        xrayBufferSizeKiB = xrayBufferSizeKiB,
        tunMtu = tunMtu,
    )
}
