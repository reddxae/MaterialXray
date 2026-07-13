package com.material.xray.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayServiceModeTest {

    @Test
    fun `always-on VPN forces Android VpnService when root service is available`() {
        assertFalse(
            shouldUseRootService(
                requested = true,
                available = true,
                alwaysOnVpn = true,
            ),
        )
    }

    @Test
    fun `root service is used when requested and available outside always-on VPN`() {
        assertTrue(
            shouldUseRootService(
                requested = true,
                available = true,
                alwaysOnVpn = false,
            ),
        )
    }
}
