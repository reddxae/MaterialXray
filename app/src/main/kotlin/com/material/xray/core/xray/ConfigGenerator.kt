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
    private val json = Json

    fun generate(
        server: ServerConfig,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query",
        domesticDnsServers: String = "",
        syntheticDnsAddress: String? = null,
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
        inbounds: List<XrayInbound>? = null,
    ): String {
        val effectiveInbounds = inbounds ?: buildList {
            add(XrayInbound.Tun(tunName, "tun-in", tunMtu))
            appProxyRoutes.forEach { route -> add(XrayInbound.Tun(route.tunName, route.inboundTag, tunMtu)) }
        }
        val dataInboundTags = effectiveInbounds.map { it.tag }
        val bootstrapDnsHosts = bootstrapDnsHosts(server, appProxyRoutes)
        if (server.rawConfigJson.isNotBlank()) {
            return injectTunIntoRawConfig(
                rawJson = server.rawConfigJson,
                tunName = tunName,
                fwmark = fwmark,
                dnsServers = dnsServers,
                domesticDnsServers = domesticDnsServers,
                syntheticDnsAddress = syntheticDnsAddress,
                bootstrapDnsHosts = bootstrapDnsHosts,
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
                inbounds = effectiveInbounds,
            )
        }

        val config = buildJsonObject {
            put("log", buildLogConfig(logLevel))
            put("dns", buildDns(dnsServers, domesticDnsServers, bootstrapDnsHosts, routingRules, bypassLan, allowIpv6))
            put(
                "inbounds",
                buildJsonArray {
                    effectiveInbounds.forEach { add(it.toJson()) }
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
                    syntheticDnsAddress = syntheticDnsAddress,
                    domainStrategy = routingDomainStrategy,
                    domainMatcher = routingDomainMatcher,
                    allowIpv6 = allowIpv6,
                    dataInboundTags = dataInboundTags,
                ),
            )
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    fun injectTunIntoRawConfig(
        rawJson: String,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query",
        domesticDnsServers: String = "",
        syntheticDnsAddress: String? = null,
        bootstrapDnsHosts: Map<String, List<String>> = emptyMap(),
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
        inbounds: List<XrayInbound>? = null,
    ): String = RawConfigTunInjector(json).inject(
        rawJson = rawJson,
        tunName = tunName,
        fwmark = fwmark,
        dnsServers = dnsServers,
        domesticDnsServers = domesticDnsServers,
        syntheticDnsAddress = syntheticDnsAddress,
        bootstrapDnsHosts = bootstrapDnsHosts,
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
        inbounds = inbounds,
    )

    /**
     * Rewrites the keys the app owns per connect into a hand-edited config, leaving every other key
     * exactly as the user wrote it.
     *
     * A hand-edited config is captured from one connect but replayed on all later ones, and the
     * runtime identity is not stable between them: the control API endpoint is a fresh abstract
     * socket name or loopback port every time, and the TUN name is whatever was free. Replaying the
     * captured values would point the stats and routing clients at an endpoint nothing is listening
     * on, and would leave the port the core actually opens outside the loopback firewall rule that
     * was installed for the fresh one.
     *
     * Returns null when [configJson] is not a JSON object, so the caller can fall back rather than
     * hand the core a document it cannot patch.
     */
    fun applyRuntimeIdentity(
        configJson: String,
        tunName: String,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
        inbounds: List<XrayInbound>? = null,
    ): String? {
        val original = runCatching { json.parseToJsonElement(configJson) as? JsonObject }.getOrNull() ?: return null
        val effectiveInbounds = inbounds ?: buildList {
            add(XrayInbound.Tun(tunName, "tun-in", tunMtu))
            appProxyRoutes.forEach { route -> add(XrayInbound.Tun(route.tunName, route.inboundTag, tunMtu)) }
        }

        val patched = original.toMutableMap()
        patched["inbounds"] = buildJsonArray { effectiveInbounds.forEach { add(it.toJson()) } }
        patched["api"] = buildStatsApi(xrayApiEndpoint)
        return json.encodeToString(JsonObject.serializer(), JsonObject(patched))
    }

    private fun bootstrapDnsHosts(
        server: ServerConfig,
        appProxyRoutes: List<AppProxyRoute>,
    ): Map<String, List<String>> = (listOf(server) + appProxyRoutes.map { it.server })
        .flatMap { it.bootstrapDnsHosts.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, addressLists) -> addressLists.flatten().distinct() }
}
