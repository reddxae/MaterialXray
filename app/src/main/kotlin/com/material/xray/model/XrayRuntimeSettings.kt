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
    val xrayBufferSizeKiB: Int = DEFAULT_XRAY_BUFFER_SIZE_KIB,
    val tunMtu: Int = DEFAULT_TUN_MTU,
    val routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
    val routingDomainMatcher: String? = null,
) {
    companion object {
        const val DEFAULT_XRAY_BUFFER_SIZE_KIB = 512
        const val MIN_XRAY_BUFFER_SIZE_KIB = 1
        const val MAX_XRAY_BUFFER_SIZE_KIB = 10_240
        const val DEFAULT_TUN_MTU = 1500
        const val MIN_TUN_MTU = 1280
        const val MAX_TUN_MTU = 1500

        fun isValidXrayBufferSizeKiB(value: Int): Boolean = value in MIN_XRAY_BUFFER_SIZE_KIB..MAX_XRAY_BUFFER_SIZE_KIB

        fun normalizeXrayBufferSizeKiB(value: Int?): Int = value
            ?.takeIf(::isValidXrayBufferSizeKiB)
            ?: DEFAULT_XRAY_BUFFER_SIZE_KIB

        fun isValidTunMtu(value: Int): Boolean = value in MIN_TUN_MTU..MAX_TUN_MTU

        fun normalizeTunMtu(value: Int?): Int = value?.takeIf(::isValidTunMtu) ?: DEFAULT_TUN_MTU
    }
}
