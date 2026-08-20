package com.material.xray.service

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemAutostartManagerTest {
    @Test
    fun `maps OEM families to their current autostart settings`() {
        assertEquals("com.miui.securitycenter", oemAutostartTargets("Xiaomi").first().packageName)
        assertEquals("com.oplus.battery", oemAutostartTargets("realme").first().packageName)
        assertEquals("com.vivo.permissionmanager", oemAutostartTargets("vivo").first().packageName)
        assertEquals("com.hihonor.systemmanager", oemAutostartTargets("HONOR").first().packageName)
        assertEquals("com.transsion.phonemaster", oemAutostartTargets("Infinix Mobility Limited").first().packageName)
        assertEquals("com.letv.android.letvsafe", oemAutostartTargets("LeMobile").first().packageName)
        assertEquals("com.meizu.safe.security.SHOW_APPSEC", oemAutostartTargets("Meizu").first().action)
        assertTrue(oemAutostartTargets("Google").isEmpty())
    }

    @Test
    fun `recognizes Xiaomi family manufacturer names`() {
        assertTrue(isXiaomiManufacturer("Xiaomi"))
        assertTrue(isXiaomiManufacturer("Redmi"))
        assertTrue(isXiaomiManufacturer("POCO"))
        assertFalse(isXiaomiManufacturer("OPPO"))
    }

    @Test
    fun `builds and parses Xiaomi autostart app-op commands`() {
        val command = xiaomiAutostartGrantCommand("com.material.xray")
        assertTrue(command.contains("--bind userAccept:l:\$((accept | 16384))"))
        assertTrue(command.contains("--bind userPrompt:l:\$((prompt & ~16384))"))
        assertTrue(command.contains("--bind userReject:l:\$((reject & ~16384))"))
        assertTrue(command.endsWith("cmd appops get com.material.xray 10008"))
        assertEquals(
            AppOpsManager.MODE_ALLOWED,
            parseXiaomiAutostartMode("MIUIOP(10008): allow; time=+1s ago"),
        )
        assertEquals(AppOpsManager.MODE_IGNORED, parseXiaomiAutostartMode("MIUIOP(10008): ignore"))
    }
}
