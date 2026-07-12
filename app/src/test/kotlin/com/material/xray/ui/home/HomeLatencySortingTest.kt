package com.material.xray.ui.home

import org.junit.Assert.assertEquals
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
}
