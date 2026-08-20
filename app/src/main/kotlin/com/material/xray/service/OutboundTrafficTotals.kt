package com.material.xray.service

/**
 * Byte totals split by whether the outbound proxied the traffic or let it leave directly.
 *
 * Only proxied traffic is split by direction, because that is the only side the connection banner
 * reports; the notification wants a single figure for direct traffic.
 */
internal data class OutboundTrafficTotals(
    val proxyUplinkBytes: Long,
    val proxyDownlinkBytes: Long,
    val directBytes: Long,
) {
    val proxyBytes: Long get() = proxyUplinkBytes + proxyDownlinkBytes
}

/**
 * Folds Xray's `outbound>>><tag>>>>traffic>>><direction>` counters into proxy and direct totals.
 *
 * Every outbound that is not one of the core's own service outbounds counts as proxying, because a
 * raw config may name its proxy outbounds anything at all: matching the literal tag `proxy` would
 * report zero traffic for every balancer config.
 *
 * Returns `null` when the map carries no outbound traffic counters, which means the stats API
 * answered before the core recorded anything and the caller has no reading yet rather than a
 * reading of zero.
 */
internal fun Map<String, Long>.readOutboundTraffic(): OutboundTrafficTotals? {
    var proxyUplink = 0L
    var proxyDownlink = 0L
    var direct = 0L
    var sawCounter = false

    forEach { (key, value) ->
        val parts = key.split(STATS_KEY_SEPARATOR)
        if (parts.size != STATS_KEY_PARTS || parts[0] != "outbound" || parts[2] != "traffic") return@forEach
        val uplink = when (parts[3]) {
            "uplink" -> true
            "downlink" -> false
            else -> return@forEach
        }
        sawCounter = true
        val tag = parts[1]
        when {
            tag == DIRECT_OUTBOUND_TAG -> direct += value
            tag in SERVICE_OUTBOUND_TAGS -> Unit
            else -> if (uplink) proxyUplink += value else proxyDownlink += value
        }
    }

    if (!sawCounter) return null
    return OutboundTrafficTotals(
        proxyUplinkBytes = proxyUplink,
        proxyDownlinkBytes = proxyDownlink,
        directBytes = direct,
    )
}

private const val DIRECT_OUTBOUND_TAG = "direct"
private val SERVICE_OUTBOUND_TAGS = setOf("dns-out", "block")
private const val STATS_KEY_SEPARATOR = ">>>"
private const val STATS_KEY_PARTS = 4
