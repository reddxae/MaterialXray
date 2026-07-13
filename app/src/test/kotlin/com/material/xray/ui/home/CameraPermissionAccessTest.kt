package com.material.xray.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionAccessTest {
    @Test
    fun `first camera request is requestable`() {
        assertEquals(
            CameraPermissionAccess.Requestable,
            resolveCameraPermissionAccess(
                granted = false,
                shouldShowRationale = false,
                permissionRequested = false,
            ),
        )
    }

    @Test
    fun `temporary denial presents rationale`() {
        assertEquals(
            CameraPermissionAccess.Rationale,
            resolveCameraPermissionAccess(
                granted = false,
                shouldShowRationale = true,
                permissionRequested = true,
            ),
        )
    }

    @Test
    fun `permanent denial directs user to app settings`() {
        assertEquals(
            CameraPermissionAccess.SystemSettings,
            resolveCameraPermissionAccess(
                granted = false,
                shouldShowRationale = false,
                permissionRequested = true,
            ),
        )
    }

    @Test
    fun `granted camera access opens scanner directly`() {
        assertEquals(
            CameraPermissionAccess.Granted,
            resolveCameraPermissionAccess(
                granted = true,
                shouldShowRationale = false,
                permissionRequested = true,
            ),
        )
    }
}
