package com.material.xray.model

data class XrayRuntimeSettings(
    val tunName: String,
    val fwmark: Int,
    val routeTable: Int,
    val useRootService: Boolean,
    val dnsServers: String,
    val domesticDnsServers: String,
    val logLevel: XrayLogLevel,
    val defaultOutbound: XrayOutbound,
    val bypassLan: Boolean,
    val allowIpv6: Boolean,
    val routingRules: List<RoutingRule>,
    val routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
    val routingDomainMatcher: String? = null,
)
