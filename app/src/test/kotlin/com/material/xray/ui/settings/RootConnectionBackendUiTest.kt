package com.material.xray.ui.settings

import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.model.RootConnectionBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootConnectionBackendUiTest {
    @Test
    fun `TUN MTU is hidden only for active root TPROXY`() {
        assertTrue(shouldShowTunMtu(rootServiceActive = false, RootConnectionBackend.Tproxy))
        assertTrue(shouldShowTunMtu(rootServiceActive = true, RootConnectionBackend.Tun))
        assertFalse(shouldShowTunMtu(rootServiceActive = true, RootConnectionBackend.Tproxy))
    }

    @Test
    fun `IPv6 stays optimistic until an IPv6 failure is confirmed`() {
        assertTrue(
            isIpv6SelectionEnabled(
                rootServiceActive = true,
                RootConnectionBackend.Tproxy,
                TproxyCompatibility.Unknown,
            ),
        )
        assertTrue(
            isIpv6SelectionEnabled(
                rootServiceActive = false,
                RootConnectionBackend.Tproxy,
                TproxyCompatibility.Supported(ipv6 = false, socketMatchOptimization = false),
            ),
        )
        assertFalse(
            isIpv6SelectionEnabled(
                rootServiceActive = true,
                RootConnectionBackend.Tproxy,
                TproxyCompatibility.Supported(ipv6 = false, socketMatchOptimization = false),
            ),
        )
    }
}
