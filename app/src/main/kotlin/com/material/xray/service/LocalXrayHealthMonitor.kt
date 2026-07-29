package com.material.xray.service

internal data class LocalHealthTransition(
    val consecutiveFailures: Int,
    val thresholdReached: Boolean,
    val recovered: Boolean,
)

internal class LocalXrayHealthMonitor(
    private val memoryCheckIntervalMs: Long,
    private val apiProbeIntervalMs: Long,
    private val snapshotIntervalMs: Long,
    tunnelFailureThreshold: Int,
    apiFailureThreshold: Int,
) {
    private val tunnelFailures = ConsecutiveFailureDetector(tunnelFailureThreshold)
    private val apiFailures = ConsecutiveFailureDetector(apiFailureThreshold)
    private var lastMemoryCheckAtMs: Long? = null
    private var lastApiProbeAtMs: Long? = null
    private var lastSnapshotAtMs: Long? = null

    fun recordTunnelAvailability(available: Boolean): LocalHealthTransition = tunnelFailures.record(available)

    fun shouldCheckMemory(nowMs: Long): Boolean = isDue(lastMemoryCheckAtMs, nowMs, memoryCheckIntervalMs).also { due ->
        if (due) lastMemoryCheckAtMs = nowMs
    }

    fun shouldProbeApi(nowMs: Long): Boolean = isDue(lastApiProbeAtMs, nowMs, apiProbeIntervalMs).also { due ->
        if (due) lastApiProbeAtMs = nowMs
    }

    fun recordApiResponsiveness(responsive: Boolean): LocalHealthTransition = apiFailures.record(responsive)

    fun shouldRecordSnapshot(nowMs: Long): Boolean = isDue(lastSnapshotAtMs, nowMs, snapshotIntervalMs).also { due ->
        if (due) lastSnapshotAtMs = nowMs
    }

    private fun isDue(lastAtMs: Long?, nowMs: Long, intervalMs: Long): Boolean = lastAtMs == null || nowMs - lastAtMs >= intervalMs
}

private class ConsecutiveFailureDetector(
    private val threshold: Int,
) {
    private var failures = 0

    init {
        require(threshold > 0)
    }

    fun record(success: Boolean): LocalHealthTransition {
        if (success) {
            val previousFailures = failures
            failures = 0
            return LocalHealthTransition(
                consecutiveFailures = previousFailures,
                thresholdReached = false,
                recovered = previousFailures > 0,
            )
        }

        failures++
        return LocalHealthTransition(
            consecutiveFailures = failures,
            thresholdReached = failures == threshold,
            recovered = false,
        )
    }
}
