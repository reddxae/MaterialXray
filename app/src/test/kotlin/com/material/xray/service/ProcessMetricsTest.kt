package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessMetricsTest {
    @Test
    fun `process metrics parser rounds resident memory and keeps socket count`() {
        assertEquals(
            ProcessMetrics(residentMemoryMb = 2, activeConnectionCount = 17),
            parseProcessMetrics("rss_kb=1025\nsockets=17"),
        )
    }

    @Test
    fun `process metrics parser tolerates one unavailable metric`() {
        assertEquals(
            ProcessMetrics(residentMemoryMb = null, activeConnectionCount = 0),
            parseProcessMetrics("rss_kb=\nsockets=0"),
        )
        assertNull(parseProcessMetrics("unrelated=value"))
    }
}
