package com.material.xray.service

import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.core.xray.XrayState
import com.material.xray.model.PingMethod
import com.material.xray.model.RootConnectionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayServiceModeTest {

    @Test
    fun `ordinary active ping uses end-to-end HTTP probing`() {
        assertEquals(
            PingMethod.Httping,
            activePingMethod(hasEditedRuntimeConfig = false, proxyOutboundCount = null),
        )
    }

    @Test
    fun `edited and multi-outbound configs do not probe a potentially different server`() {
        assertNull(activePingMethod(hasEditedRuntimeConfig = true, proxyOutboundCount = null))
        assertNull(activePingMethod(hasEditedRuntimeConfig = false, proxyOutboundCount = 2))
    }

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

    @Test
    fun `root TPROXY falls back to IPv4 when dual stack is unavailable`() {
        assertFalse(
            effectiveTproxyIpv6(
                requested = true,
                useRootService = true,
                backend = RootConnectionBackend.Tproxy,
                compatibility = TproxyCompatibility.Supported(ipv6 = false),
            ),
        )
        assertTrue(
            effectiveTproxyIpv6(
                requested = true,
                useRootService = false,
                backend = RootConnectionBackend.Tproxy,
                compatibility = TproxyCompatibility.Supported(ipv6 = false),
            ),
        )
    }

    @Test
    fun `passive monitoring verifies stable root route`() {
        assertTrue(
            shouldVerifyRootRoute(
                passiveHealthMonitoringEnabled = true,
                networkChanged = false,
                networkCallbacksAvailable = true,
            ),
        )
    }

    @Test
    fun `disabled passive monitoring retains network change fallback`() {
        assertTrue(
            shouldVerifyRootRoute(
                passiveHealthMonitoringEnabled = false,
                networkChanged = true,
                networkCallbacksAvailable = true,
            ),
        )
        assertTrue(
            shouldVerifyRootRoute(
                passiveHealthMonitoringEnabled = false,
                networkChanged = false,
                networkCallbacksAvailable = false,
            ),
        )
    }

    @Test
    fun `disabled passive monitoring skips stable periodic root verification`() {
        assertFalse(
            shouldVerifyRootRoute(
                passiveHealthMonitoringEnabled = false,
                networkChanged = false,
                networkCallbacksAvailable = true,
            ),
        )
    }

    @Test
    fun `new Android network on same physical interface keeps core running`() {
        assertFalse(
            shouldReconnectForNetworkChange(
                previousInterface = "wlan0",
                currentInterface = "wlan0",
            ),
        )
        assertTrue(
            shouldReconnectForNetworkChange(
                previousInterface = "wlan0",
                currentInterface = "rmnet0",
            ),
        )
    }

    @Test
    fun `runtime restoration requires the same app version`() {
        assertTrue(isRuntimeVersionCompatible(recordedVersionCode = 600, currentVersionCode = 600))
        assertFalse(isRuntimeVersionCompatible(recordedVersionCode = 599, currentVersionCode = 600))
        assertFalse(isRuntimeVersionCompatible(recordedVersionCode = null, currentVersionCode = 600))
    }

    @Test
    fun `package recovery only runs root cleanup for root runtimes`() {
        assertTrue(
            shouldCleanRecordedRootRuntime(
                XrayState(physicalInterface = "wlan0"),
                connectIfMissing = true,
            ),
        )
        assertFalse(
            shouldCleanRecordedRootRuntime(
                XrayState(physicalInterface = VPN_SERVICE_INTERFACE_LABEL),
                connectIfMissing = true,
            ),
        )
        assertFalse(shouldCleanRecordedRootRuntime(null, connectIfMissing = true))
    }
}
