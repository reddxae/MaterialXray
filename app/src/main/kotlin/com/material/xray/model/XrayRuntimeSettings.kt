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
    val routingRules: List<RoutingRule>,
)
