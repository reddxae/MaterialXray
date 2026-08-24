package com.material.xray.ui.home

import com.material.xray.model.PingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLatencySortingTest {

    @Test
    fun `sorts completed results while probes remain in progress and keeps ties stable`() {
        val serverIds = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        val latencies = mapOf(
            1L to ServerLatencyState(250),
            2L to ServerLatencyState(-1),
            3L to ServerLatencyState(50),
            4L to ServerLatencyState(50),
            5L to ServerLatencyState(LATENCY_TESTING),
        )

        assertEquals(
            listOf(3L, 4L, 1L, 2L, 5L, 6L),
            sortedServerIdsByLatency(serverIds, latencies),
        )
    }

    @Test
    fun `uses only the selected latency method when dual results are disabled`() {
        assertEquals(
            listOf(PingMethod.Httping),
            latencyMethods(primaryMethod = PingMethod.Httping, showBoth = false),
        )
    }

    @Test
    fun `uses tcping then httping when dual results are enabled`() {
        assertEquals(
            listOf(PingMethod.Tcping, PingMethod.Httping),
            latencyMethods(primaryMethod = PingMethod.Httping, showBoth = true),
        )
    }

    @Test
    fun `shows an error when the only latency result is unavailable`() {
        assertTrue(latencyShowsError(ServerLatencyState(latencyMs = -1)))
        assertFalse(latencyShowsError(ServerLatencyState(latencyMs = 34)))
        assertFalse(latencyShowsError(ServerLatencyState(latencyMs = LATENCY_TESTING)))
    }

    @Test
    fun `uses only httping availability for the dual result error`() {
        assertFalse(
            latencyShowsError(
                ServerLatencyState(
                    latencyMs = 132,
                    tcpingLatencyMs = -1,
                    httpingLatencyMs = 132,
                ),
            ),
        )
        assertTrue(
            latencyShowsError(
                ServerLatencyState(
                    latencyMs = -1,
                    tcpingLatencyMs = 34,
                    httpingLatencyMs = -1,
                ),
            ),
        )
    }
}
