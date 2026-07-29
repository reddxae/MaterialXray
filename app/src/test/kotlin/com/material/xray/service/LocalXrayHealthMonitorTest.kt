package com.material.xray.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalXrayHealthMonitorTest {
    @Test
    fun `structural failure requires configured consecutive observations`() {
        val monitor = monitor(tunnelFailureThreshold = 2)

        val firstFailure = monitor.recordTunnelAvailability(available = false)
        val secondFailure = monitor.recordTunnelAvailability(available = false)
        val thirdFailure = monitor.recordTunnelAvailability(available = false)

        assertFalse(firstFailure.thresholdReached)
        assertTrue(secondFailure.thresholdReached)
        assertFalse(thirdFailure.thresholdReached)
        assertTrue(thirdFailure.consecutiveFailures > secondFailure.consecutiveFailures)
    }

    @Test
    fun `successful observation clears prior failures`() {
        val monitor = monitor(apiFailureThreshold = 3)

        monitor.recordApiResponsiveness(responsive = false)
        monitor.recordApiResponsiveness(responsive = false)
        val recovery = monitor.recordApiResponsiveness(responsive = true)
        val nextFailure = monitor.recordApiResponsiveness(responsive = false)

        assertTrue(recovery.recovered)
        assertFalse(nextFailure.thresholdReached)
    }

    @Test
    fun `API probes and snapshots are rate limited independently`() {
        val monitor = monitor(apiProbeIntervalMs = 60_000L, snapshotIntervalMs = 300_000L)

        assertTrue(monitor.shouldProbeApi(10_000L))
        assertFalse(monitor.shouldProbeApi(69_999L))
        assertTrue(monitor.shouldProbeApi(70_000L))

        assertTrue(monitor.shouldRecordSnapshot(10_000L))
        assertFalse(monitor.shouldRecordSnapshot(309_999L))
        assertTrue(monitor.shouldRecordSnapshot(310_000L))
    }

    @Test
    fun `memory checks use their own rate limit`() {
        val monitor = monitor(memoryCheckIntervalMs = 60_000L)

        assertTrue(monitor.shouldCheckMemory(10_000L))
        assertFalse(monitor.shouldCheckMemory(69_999L))
        assertTrue(monitor.shouldCheckMemory(70_000L))
    }

    private fun monitor(
        memoryCheckIntervalMs: Long = 60_000L,
        apiProbeIntervalMs: Long = 60_000L,
        snapshotIntervalMs: Long = 300_000L,
        tunnelFailureThreshold: Int = 2,
        apiFailureThreshold: Int = 3,
    ) = LocalXrayHealthMonitor(
        memoryCheckIntervalMs = memoryCheckIntervalMs,
        apiProbeIntervalMs = apiProbeIntervalMs,
        snapshotIntervalMs = snapshotIntervalMs,
        tunnelFailureThreshold = tunnelFailureThreshold,
        apiFailureThreshold = apiFailureThreshold,
    )
}
