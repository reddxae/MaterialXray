package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsPollingTest {
    @Test
    fun `nothing is polled when neither consumer is watching`() {
        assertNull(
            metricsPollIntervalMs(
                notificationIntervalMs = 2_000,
                notificationWantsMetrics = false,
                uiWantsSessionTraffic = false,
            ),
        )
    }

    @Test
    fun `the notification alone is polled at the interval it configured`() {
        assertEquals(
            2_000,
            metricsPollIntervalMs(
                notificationIntervalMs = 2_000,
                notificationWantsMetrics = true,
                uiWantsSessionTraffic = false,
            ),
        )
    }

    @Test
    fun `the banner alone is polled at its own cadence`() {
        assertEquals(
            SESSION_TRAFFIC_POLL_INTERVAL_MS,
            metricsPollIntervalMs(
                notificationIntervalMs = 5_000,
                notificationWantsMetrics = false,
                uiWantsSessionTraffic = true,
            ),
        )
    }

    @Test
    fun `two consumers are served by the faster of the two cadences`() {
        assertEquals(
            SESSION_TRAFFIC_POLL_INTERVAL_MS,
            metricsPollIntervalMs(
                notificationIntervalMs = 5_000,
                notificationWantsMetrics = true,
                uiWantsSessionTraffic = true,
            ),
        )
        assertEquals(
            250,
            metricsPollIntervalMs(
                notificationIntervalMs = 250,
                notificationWantsMetrics = true,
                uiWantsSessionTraffic = true,
            ),
        )
    }

    @Test
    fun `a rate is the byte delta spread over the elapsed time`() {
        assertEquals(2_048L, bytesPerSecond(currentBytes = 3_048, previousBytes = 1_000, elapsedMs = 1_000))
        assertEquals(4_096L, bytesPerSecond(currentBytes = 3_048, previousBytes = 1_000, elapsedMs = 500))
    }

    @Test
    fun `a counter that restarted reads as idle rather than as a negative rate`() {
        assertEquals(0L, bytesPerSecond(currentBytes = 10, previousBytes = 5_000, elapsedMs = 1_000))
    }

    @Test
    fun `two readings in the same millisecond do not divide by zero`() {
        assertEquals(1_000L, bytesPerSecond(currentBytes = 1, previousBytes = 0, elapsedMs = 0))
    }
}
