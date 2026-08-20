package com.material.xray.model

/**
 * Live throughput and cumulative totals for the current core session, covering every proxying
 * outbound. Byte totals come from Xray's own counters, which start at zero when the core starts,
 * so they measure the session rather than the lifetime of the app.
 *
 * The rates are null until two samples exist, which is the case for the first reading after the
 * poller starts.
 */
data class SessionTrafficMetrics(
    val uplinkBps: Long?,
    val downlinkBps: Long?,
    val uplinkBytes: Long,
    val downlinkBytes: Long,
)
