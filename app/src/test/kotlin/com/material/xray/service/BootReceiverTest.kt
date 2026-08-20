package com.material.xray.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun `boot and package replacement trigger automatic connection`() {
        assertTrue(isAutoConnectTrigger(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(isAutoConnectTrigger(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `unrelated and missing actions are ignored`() {
        assertFalse(isAutoConnectTrigger(Intent.ACTION_PACKAGE_REPLACED))
        assertFalse(isAutoConnectTrigger(null))
    }

    @Test
    fun `automatic connection starts only when enabled and authorized`() {
        assertTrue(shouldStartAutomaticConnection(enabled = true, vpnPermissionGranted = true))
        assertFalse(shouldStartAutomaticConnection(enabled = false, vpnPermissionGranted = true))
        assertFalse(shouldStartAutomaticConnection(enabled = true, vpnPermissionGranted = false))
    }

    @Test
    fun `package replacement restores a surviving runtime before reconnecting`() {
        assertTrue(
            shouldRecoverAfterPackageReplacement(
                Intent.ACTION_MY_PACKAGE_REPLACED,
                autoConnect = false,
                hasRecordedRuntime = true,
            ),
        )
        assertTrue(
            shouldRecoverAfterPackageReplacement(
                Intent.ACTION_MY_PACKAGE_REPLACED,
                autoConnect = true,
                hasRecordedRuntime = false,
            ),
        )
        assertFalse(
            shouldRecoverAfterPackageReplacement(
                Intent.ACTION_BOOT_COMPLETED,
                autoConnect = true,
                hasRecordedRuntime = true,
            ),
        )
    }
}
