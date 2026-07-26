package com.material.xray.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreUptimeFormatterTest {

    @Test
    fun `expands uptime format as larger units become necessary`() {
        assertEquals("00:00", formatCoreUptime(0L))
        assertEquals("59:59", formatCoreUptime(3_599_999L))
        assertEquals("01:02:03", formatCoreUptime(3_723_999L))
        assertEquals("23:59:59", formatCoreUptime(86_399_999L))
        assertEquals("01:01:00:00", formatCoreUptime(90_000_000L))
    }

    @Test
    fun `clamps a future start time to zero uptime`() {
        assertEquals("00:00", formatCoreUptime(-1L))
    }
}
