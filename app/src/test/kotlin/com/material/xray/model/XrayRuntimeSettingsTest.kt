package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayRuntimeSettingsTest {

    @Test
    fun `normalizes xray buffer size`() {
        assertEquals(64, XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB)
        assertEquals(XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB, XrayRuntimeSettings.normalizeXrayBufferSizeKiB(null))
        assertEquals(XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB, XrayRuntimeSettings.normalizeXrayBufferSizeKiB(0))
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

    @Test
    fun `normalizes core RAM restart threshold`() {
        assertEquals(200, XrayRuntimeSettings.normalizeXrayMemoryRestartThresholdMiB(null))
        assertEquals(200, XrayRuntimeSettings.normalizeXrayMemoryRestartThresholdMiB(63))
        assertEquals(512, XrayRuntimeSettings.normalizeXrayMemoryRestartThresholdMiB(512))
        assertTrue(XrayRuntimeSettings.isValidXrayMemoryRestartThresholdMiB(32_768))
        assertFalse(XrayRuntimeSettings.isValidXrayMemoryRestartThresholdMiB(32_769))
    }

    @Test
    fun `restarts only when core RAM usage exceeds threshold`() {
        assertFalse(XrayRuntimeSettings.shouldRestartForMemory(null, 200))
        assertFalse(XrayRuntimeSettings.shouldRestartForMemory(200, 200))
        assertTrue(XrayRuntimeSettings.shouldRestartForMemory(201, 200))
    }
}
