package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsTest {
    @Test
    fun `a ping-only notification does not start the metrics poll`() {
        val settings = NotificationSettings(showPing = true)

        assertTrue(settings.anyFieldEnabled)
        assertTrue(settings.needsPingProbe)
        assertFalse(settings.needsMetricsPoll)
    }

    @Test
    fun `session traffic is served by the metrics poll`() {
        val settings = NotificationSettings(showSessionTraffic = true)

        assertTrue(settings.needsMetricsPoll)
        assertFalse(settings.needsPingProbe)
    }

    @Test
    fun `a disabled notification asks for no work regardless of its fields`() {
        val settings = NotificationSettings(
            enabled = false,
            showTrafficSpeed = true,
            showPing = true,
            showSessionTraffic = true,
        )

        assertFalse(settings.needsMetricsPoll)
        assertFalse(settings.needsPingProbe)
    }

    @Test
    fun `every field reports its own toggle`() {
        val settings = NotificationSettings(
            showTrafficSpeed = true,
            showRamUsage = false,
            showConnectionCount = true,
            showPing = false,
            showSessionTraffic = true,
        )

        assertTrue(settings.isFieldEnabled(NotificationField.TrafficSpeed))
        assertFalse(settings.isFieldEnabled(NotificationField.RamUsage))
        assertTrue(settings.isFieldEnabled(NotificationField.ConnectionCount))
        assertFalse(settings.isFieldEnabled(NotificationField.Ping))
        assertTrue(settings.isFieldEnabled(NotificationField.SessionTraffic))
    }

    @Test
    fun `a saved order from before a field existed still lists every field`() {
        val settings = NotificationSettings(
            fieldOrder = listOf(NotificationField.ConnectionCount, NotificationField.TrafficSpeed),
        )

        val order = settings.normalizedFieldOrder()

        assertEquals(NotificationField.entries.size, order.size)
        assertEquals(NotificationField.ConnectionCount, order.first())
        assertTrue(order.containsAll(NotificationField.entries))
    }
}
