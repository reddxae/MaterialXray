package com.material.xray.model

/**
 * Live throughput and cumulative totals for the current core session, covering every proxying
 * outbound. Byte totals come from Xray's own counters, which start at zero when the core starts,
 * so they measure the session rather than the lifetime of the app.
 *
 * Only complete readings are reported. A rate needs two samples of the counters, so a reading that
 * has totals but no rate yet is withheld rather than published with the rates missing.
 */
data class SessionTrafficMetrics(
    val uplinkBps: Long,
    val downlinkBps: Long,
    val uplinkBytes: Long,
    val downlinkBytes: Long,
)
