package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDiagnosticsLoggerTest {
    @Test
    fun `startup diagnostics include operational context without multiline values`() {
        val lines = formatStartupDiagnostics(snapshot())

        assertEquals("Startup diagnostics", lines.first())
        assertTrue(lines.any { it.contains("version=0.6.0 (600)") })
        assertTrue(lines.any { it.contains("model=Test Phone") })
        assertTrue(lines.any { it.contains("passiveWatchdog=true") })
        assertTrue(lines.any { it.contains("buffer=64 KiB") })
        assertTrue(lines.any { it.contains("rules=2/3 enabled") })
        assertTrue(lines.none { it.contains('\n') })
    }

    private fun snapshot() = StartupDiagnosticSnapshot(
        appVersion = "0.6.0",
        appVersionCode = 600,
        debugBuild = true,
        manufacturer = "Test",
        brand = "Test",
        model = "Test\nPhone",
        device = "device",
        product = "product",
        hardware = "hardware",
        supportedAbis = listOf("arm64-v8a"),
        fingerprint = "test/fingerprint",
        androidRelease = "16",
        sdk = 36,
        securityPatch = "2026-07-01",
        incremental = "123",
        kernel = "6.1",
        totalMemoryMiB = 8192,
        memoryClassMiB = 256,
        lowRamDevice = false,
        batteryOptimizationsIgnored = true,
        lowPowerStandbyExempt = null,
        serviceMode = "VpnService",
        autoConnect = false,
        passiveHealthMonitoring = true,
        tunName = "tun0",
        tunMtu = 1500,
        xrayBufferSizeKiB = 64,
        memoryRestartThresholdMiB = 200,
        bypassLan = true,
        allowIpv6 = false,
        defaultOutbound = "proxy",
        xrayLogLevel = "none",
        dnsServers = "1.1.1.1,1.0.0.1",
        domesticDnsServers = "77.88.8.8,77.88.8.1",
        routingDomainStrategy = "IPIfNonMatch",
        routingDomainMatcher = null,
        routingFallbackOutbound = null,
        routingRuleCount = 3,
        enabledRoutingRuleCount = 2,
    )
}
