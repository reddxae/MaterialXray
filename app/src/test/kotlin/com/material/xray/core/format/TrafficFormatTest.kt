package com.material.xray.core.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficFormatTest {
    @Test
    fun `whole bytes are reported without a fraction`() {
        val scaled = scaleBytes(512, Locale.ROOT)

        assertEquals("512", scaled.value)
        assertEquals(TrafficMagnitude.Bytes, scaled.magnitude)
    }

    @Test
    fun `a value climbs to the next unit only once it reaches the full step`() {
        assertEquals(TrafficMagnitude.Bytes, scaleBytes(1023, Locale.ROOT).magnitude)
        assertEquals(TrafficMagnitude.Kibibytes, scaleBytes(1024, Locale.ROOT).magnitude)
    }

    @Test
    fun `scaled values keep one decimal`() {
        val scaled = scaleBytes(1_572_864, Locale.ROOT)

        assertEquals("1.5", scaled.value)
        assertEquals(TrafficMagnitude.Mebibytes, scaled.magnitude)
    }

    @Test
    fun `scaling stops at the largest known unit`() {
        val scaled = scaleBytes(4L * 1024 * 1024 * 1024 * 1024 * 1024, Locale.ROOT)

        assertEquals(TrafficMagnitude.Tebibytes, scaled.magnitude)
        assertEquals("4,096.0", scaled.value)
    }

    @Test
    fun `a counter that went backwards reads as zero rather than a negative rate`() {
        val scaled = scaleBytes(-1, Locale.ROOT)

        assertEquals("0", scaled.value)
        assertEquals(TrafficMagnitude.Bytes, scaled.magnitude)
    }
}
