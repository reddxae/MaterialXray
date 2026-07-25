package com.material.xray.service

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import com.material.xray.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class StartupDiagnosticsLogger @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val logBuffer: LogBuffer,
) {
    private val mutex = Mutex()

    suspend fun log() = mutex.withLock {
        recordSnapshot()
    }

    suspend fun logIfMissing() = mutex.withLock {
        if (logBuffer.entries.value.any { it.source == LogSource.APP && it.message == STARTUP_DIAGNOSTICS_HEADER }) {
            return@withLock
        }
        recordSnapshot()
    }

    private suspend fun recordSnapshot() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val runtimeSettings = settingsRepository.runtimeSettingsSnapshot()
        val snapshot = StartupDiagnosticSnapshot(
            appVersion = packageInfo.versionName.orEmpty(),
            appVersionCode = packageInfo.longVersionCode,
            debugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            hardware = Build.HARDWARE,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            fingerprint = Build.FINGERPRINT,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            incremental = Build.VERSION.INCREMENTAL,
            kernel = System.getProperty("os.version").orEmpty(),
            totalMemoryMiB = memoryInfo.totalMem / BYTES_PER_MIB,
            memoryClassMiB = activityManager.memoryClass,
            lowRamDevice = activityManager.isLowRamDevice,
            batteryOptimizationsIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            lowPowerStandbyExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                powerManager.isExemptFromLowPowerStandby()
            } else {
                null
            },
            serviceMode = if (runtimeSettings.useRootService) "root" else "VpnService",
            autoConnect = settingsRepository.autoConnect.first(),
            passiveHealthMonitoring = settingsRepository.passiveHealthMonitoringEnabled.first(),
            tunName = runtimeSettings.tunName,
            tunMtu = runtimeSettings.tunMtu,
            xrayBufferSizeKiB = runtimeSettings.xrayBufferSizeKiB,
            memoryRestartThresholdMiB = settingsRepository.xrayMemoryRestartThresholdMiB.first(),
            bypassLan = runtimeSettings.bypassLan,
            allowIpv6 = runtimeSettings.allowIpv6,
            defaultOutbound = runtimeSettings.defaultOutbound.tag,
            xrayLogLevel = runtimeSettings.logLevel.value,
            dnsServers = runtimeSettings.dnsServers,
            domesticDnsServers = runtimeSettings.domesticDnsServers,
            routingDomainStrategy = runtimeSettings.routingDomainStrategy,
            routingDomainMatcher = runtimeSettings.routingDomainMatcher,
            routingFallbackOutbound = runtimeSettings.routingFallbackOutbound?.tag,
            routingRuleCount = runtimeSettings.routingRules.size,
            enabledRoutingRuleCount = runtimeSettings.routingRules.count { it.enabled },
        )
        logBuffer.appendAll(LogSource.APP, formatStartupDiagnostics(snapshot))
    }

    private companion object {
        const val BYTES_PER_MIB = 1024L * 1024L
        const val STARTUP_DIAGNOSTICS_HEADER = "Startup diagnostics"
    }
}

internal data class StartupDiagnosticSnapshot(
    val appVersion: String,
    val appVersionCode: Long,
    val debugBuild: Boolean,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val supportedAbis: List<String>,
    val fingerprint: String,
    val androidRelease: String,
    val sdk: Int,
    val securityPatch: String,
    val incremental: String,
    val kernel: String,
    val totalMemoryMiB: Long,
    val memoryClassMiB: Int,
    val lowRamDevice: Boolean,
    val batteryOptimizationsIgnored: Boolean,
    val lowPowerStandbyExempt: Boolean?,
    val serviceMode: String,
    val autoConnect: Boolean,
    val passiveHealthMonitoring: Boolean,
    val tunName: String,
    val tunMtu: Int,
    val xrayBufferSizeKiB: Int,
    val memoryRestartThresholdMiB: Int,
    val bypassLan: Boolean,
    val allowIpv6: Boolean,
    val defaultOutbound: String,
    val xrayLogLevel: String,
    val dnsServers: String,
    val domesticDnsServers: String,
    val routingDomainStrategy: String,
    val routingDomainMatcher: String?,
    val routingFallbackOutbound: String?,
    val routingRuleCount: Int,
    val enabledRoutingRuleCount: Int,
)

internal fun formatStartupDiagnostics(snapshot: StartupDiagnosticSnapshot): List<String> = with(snapshot) {
    listOf(
        "Startup diagnostics",
        "App: version=${appVersion.diagnosticValue()} ($appVersionCode), debug=$debugBuild",
        "Android: release=${androidRelease.diagnosticValue()}, sdk=$sdk, securityPatch=${securityPatch.diagnosticValue()}, " +
            "incremental=${incremental.diagnosticValue()}, kernel=${kernel.diagnosticValue()}",
        "Device: manufacturer=${manufacturer.diagnosticValue()}, brand=${brand.diagnosticValue()}, " +
            "model=${model.diagnosticValue()}, device=${device.diagnosticValue()}, product=${product.diagnosticValue()}, " +
            "hardware=${hardware.diagnosticValue()}, abis=${supportedAbis.joinToString(",").diagnosticValue()}",
        "Build fingerprint: ${fingerprint.diagnosticValue()}",
        "Memory: total=$totalMemoryMiB MiB, class=$memoryClassMiB MiB, lowRam=$lowRamDevice",
        "Power: batteryOptimizationsIgnored=$batteryOptimizationsIgnored, " +
            "lowPowerStandbyExempt=${lowPowerStandbyExempt?.toString() ?: "unsupported"}",
        "Connection settings: mode=$serviceMode, autoConnect=$autoConnect, passiveWatchdog=$passiveHealthMonitoring, " +
            "bypassLan=$bypassLan, ipv6=$allowIpv6",
        "Xray settings: tun=${tunName.diagnosticValue()}, mtu=$tunMtu, buffer=$xrayBufferSizeKiB KiB, " +
            "memoryRestart=$memoryRestartThresholdMiB MiB, outbound=${defaultOutbound.diagnosticValue()}, " +
            "logLevel=${xrayLogLevel.diagnosticValue()}",
        "DNS settings: primary=${dnsServers.diagnosticValue()}, domestic=${domesticDnsServers.diagnosticValue()}",
        "Routing settings: strategy=${routingDomainStrategy.diagnosticValue()}, " +
            "matcher=${routingDomainMatcher.diagnosticValue()}, fallback=${routingFallbackOutbound.diagnosticValue()}, " +
            "rules=$enabledRoutingRuleCount/$routingRuleCount enabled",
    )
}

private fun String?.diagnosticValue(): String = this
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "unset"
