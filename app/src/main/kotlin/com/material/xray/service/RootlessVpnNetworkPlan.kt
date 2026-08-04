package com.material.xray.service

internal data class VpnNetworkPrefix(
    val address: String,
    val prefixLength: Int,
)

internal data class RootlessVpnNetworkPlan(
    val addresses: List<VpnNetworkPrefix>,
    val routes: List<VpnNetworkPrefix>,
    val dnsServers: List<String>,
)

internal fun planRootlessVpnNetwork(allowIpv6: Boolean): RootlessVpnNetworkPlan = RootlessVpnNetworkPlan(
    addresses = buildList {
        add(VpnNetworkPrefix(address = VPN_IPV4_ADDRESS, prefixLength = VPN_IPV4_PREFIX_LENGTH))
        if (allowIpv6) {
            add(VpnNetworkPrefix(address = VPN_IPV6_ADDRESS, prefixLength = VPN_IPV6_PREFIX_LENGTH))
        }
    },
    routes = buildList {
        add(VpnNetworkPrefix(address = IPV4_DEFAULT_ROUTE, prefixLength = DEFAULT_ROUTE_PREFIX_LENGTH))
        if (allowIpv6) {
            add(VpnNetworkPrefix(address = IPV6_DEFAULT_ROUTE, prefixLength = DEFAULT_ROUTE_PREFIX_LENGTH))
        }
    },
    // Xray's first routing rule intercepts port 53, so Android only needs an on-link DNS target.
    dnsServers = listOf(VPN_DNS_ADDRESS),
)

private const val VPN_IPV4_ADDRESS = "10.10.14.1"
private const val VPN_IPV4_PREFIX_LENGTH = 30
private const val VPN_IPV6_ADDRESS = "fd10:10:14::1"
private const val VPN_IPV6_PREFIX_LENGTH = 64
private const val VPN_DNS_ADDRESS = "10.10.14.2"
private const val IPV4_DEFAULT_ROUTE = "0.0.0.0"
private const val IPV6_DEFAULT_ROUTE = "::"
private const val DEFAULT_ROUTE_PREFIX_LENGTH = 0
