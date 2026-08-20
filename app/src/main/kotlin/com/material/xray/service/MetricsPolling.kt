package com.material.xray.service

/** How often the connection banner wants a fresh reading while it is on screen. */
internal const val SESSION_TRAFFIC_POLL_INTERVAL_MS = 1_000

/**
 * Interval the shared metrics loop should poll Xray at, or `null` when neither the notification nor
 * the connection banner is watching and the loop should not run at all.
 *
 * When both want readings the faster cadence wins, so the banner stays live. The notification is
 * refreshed on its own configured schedule regardless, so a faster poll only sharpens the numbers
 * it shows rather than overriding the interval the user chose.
 */
internal fun metricsPollIntervalMs(
    notificationIntervalMs: Int,
    notificationWantsMetrics: Boolean,
    uiWantsSessionTraffic: Boolean,
): Int? = when {
    uiWantsSessionTraffic && notificationWantsMetrics -> minOf(notificationIntervalMs, SESSION_TRAFFIC_POLL_INTERVAL_MS)
    uiWantsSessionTraffic -> SESSION_TRAFFIC_POLL_INTERVAL_MS
    notificationWantsMetrics -> notificationIntervalMs
    else -> null
}

/**
 * Throughput between two readings of a monotonically growing counter.
 *
 * A counter that went backwards means the core restarted underneath the loop and its counters
 * began again, so the drop reads as zero rather than as a negative rate. [elapsedMs] is clamped
 * to at least a millisecond so two readings taken in the same millisecond cannot divide by zero.
 */
internal fun bytesPerSecond(currentBytes: Long, previousBytes: Long, elapsedMs: Long): Long {
    val elapsedSeconds = elapsedMs.coerceAtLeast(1).toDouble() / MILLIS_PER_SECOND
    return ((currentBytes - previousBytes).coerceAtLeast(0) / elapsedSeconds).toLong()
}

private const val MILLIS_PER_SECOND = 1000.0
