package com.material.xray.service

import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.model.RootConnectionBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TproxyBackendDemotionTest {

    @Test
    fun `a kernel that cannot run TPROXY demotes the stored backend to TUN`() {
        assertTrue(
            shouldDemoteTproxyBackend(
                TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv4Unavailable),
                RootConnectionBackend.Tproxy,
            ),
        )
    }

    @Test
    fun `an IPv6-only gap still demotes because TPROXY cannot serve the stored selection`() {
        assertTrue(
            shouldDemoteTproxyBackend(
                TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv6Unavailable),
                RootConnectionBackend.Tproxy,
            ),
        )
    }

    @Test
    fun `environment failures never rewrite the stored backend`() {
        val transient = listOf(
            TproxyCompatibility.Reason.RootUnavailable,
            TproxyCompatibility.Reason.InitNetworkNamespaceUnavailable,
            TproxyCompatibility.Reason.RouteTableConflict,
            TproxyCompatibility.Reason.MarkNamespaceConflict,
            TproxyCompatibility.Reason.ProbeCleanupFailed,
            TproxyCompatibility.Reason.CommandTimedOut,
        )

        transient.forEach { reason ->
            assertFalse(
                "reason=$reason must stay retryable instead of demoting the backend",
                shouldDemoteTproxyBackend(
                    TproxyCompatibility.Unsupported(reason),
                    RootConnectionBackend.Tproxy,
                ),
            )
        }
    }

    @Test
    fun `an inconclusive or successful probe leaves the backend alone`() {
        listOf(
            TproxyCompatibility.Unknown,
            TproxyCompatibility.Checking,
            TproxyCompatibility.Supported(ipv6 = true, socketMatchOptimization = true),
            TproxyCompatibility.Supported(ipv6 = false, socketMatchOptimization = false),
        ).forEach { result ->
            assertFalse("result=$result", shouldDemoteTproxyBackend(result, RootConnectionBackend.Tproxy))
        }
    }

    @Test
    fun `a user already on TUN is not rewritten again`() {
        assertFalse(
            shouldDemoteTproxyBackend(
                TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv4Unavailable),
                RootConnectionBackend.Tun,
            ),
        )
    }
}
