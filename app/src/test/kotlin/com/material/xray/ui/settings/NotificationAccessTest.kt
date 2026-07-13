package com.material.xray.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAccessTest {
    @Test
    fun `first Android 13 request remains requestable`() {
        assertEquals(
            NotificationAccess.Requestable,
            resolveNotificationAccess(
                permissionRequired = true,
                permissionGranted = false,
                notificationsEnabled = false,
                shouldShowRationale = false,
                permissionRequested = false,
            ),
        )
    }

    @Test
    fun `denied request with rationale remains requestable with explanation`() {
        assertEquals(
            NotificationAccess.Rationale,
            resolveNotificationAccess(
                permissionRequired = true,
                permissionGranted = false,
                notificationsEnabled = false,
                shouldShowRationale = true,
                permissionRequested = true,
            ),
        )
    }

    @Test
    fun `permanently denied request directs user to system settings`() {
        assertEquals(
            NotificationAccess.SystemSettings,
            resolveNotificationAccess(
                permissionRequired = true,
                permissionGranted = false,
                notificationsEnabled = false,
                shouldShowRationale = false,
                permissionRequested = true,
            ),
        )
    }

    @Test
    fun `globally disabled notifications require system settings even with permission`() {
        assertEquals(
            NotificationAccess.SystemSettings,
            resolveNotificationAccess(
                permissionRequired = true,
                permissionGranted = true,
                notificationsEnabled = false,
                shouldShowRationale = false,
                permissionRequested = true,
            ),
        )
    }
}
