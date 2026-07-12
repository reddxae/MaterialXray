package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayRuntimeSettingsTest {

    @Test
    fun `normalizes xray buffer size`() {
        assertEquals(512, XrayRuntimeSettings.normalizeXrayBufferSizeKiB(null))
        assertEquals(512, XrayRuntimeSettings.normalizeXrayBufferSizeKiB(0))
        assertEquals(1024, XrayRuntimeSettings.normalizeXrayBufferSizeKiB(1024))
        assertTrue(XrayRuntimeSettings.isValidXrayBufferSizeKiB(10_240))
        assertFalse(XrayRuntimeSettings.isValidXrayBufferSizeKiB(10_241))
    }

    @Test
    fun `normalizes tun MTU`() {
        assertEquals(1500, XrayRuntimeSettings.normalizeTunMtu(null))
        assertEquals(1500, XrayRuntimeSettings.normalizeTunMtu(1279))
        assertEquals(1400, XrayRuntimeSettings.normalizeTunMtu(1400))
        assertTrue(XrayRuntimeSettings.isValidTunMtu(1280))
        assertFalse(XrayRuntimeSettings.isValidTunMtu(1501))
    }
}
