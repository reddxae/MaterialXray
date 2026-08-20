package com.material.xray.service

import android.content.Context
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.core.xray.TproxyCompatibilityDetector
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.isConclusive
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.LauncherIcon
import com.material.xray.model.RootConnectionBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class SettingsRuntimeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rootShell: RootShell,
    private val geoDataManager: GeoDataManager,
    private val launcherIconManager: LauncherIconManager,
    private val appUpdateScheduler: AppUpdateScheduler,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val tproxyCompatibilityDetector: TproxyCompatibilityDetector,
    private val log: LogBuffer,
) {
    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    private val _xrayCoreVersion = MutableStateFlow<String?>(null)
    private val diagnosticsMutex = Mutex()
    private var diagnosticsLoaded = false

    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()
    val xrayCoreVersion: StateFlow<String?> = _xrayCoreVersion.asStateFlow()

    suspend fun setLauncherIcon(icon: LauncherIcon) {
        settingsRepository.setLauncherIcon(icon)
        launcherIconManager.apply(icon)
    }

    suspend fun setAppUpdateChecksEnabled(enabled: Boolean) {
        settingsRepository.setAppUpdateChecksEnabled(enabled)
        appUpdateScheduler.setEnabled(enabled)
    }

    suspend fun setUseRootService(enabled: Boolean): Boolean {
        if (!enabled) {
            settingsRepository.setUseRootService(false)
            reloadActiveConnectionIfConnected()
            return true
        }
        val available = withContext(Dispatchers.IO) { rootShell.open(RootShell.NetworkNamespace.INIT) }
        _rootAvailable.value = available
        if (!available) return false
        settingsRepository.setUseRootService(true)
        reloadActiveConnectionIfConnected()
        return true
    }

    suspend fun setRootConnectionBackend(backend: RootConnectionBackend) {
        settingsRepository.setRootConnectionBackend(backend)
        reloadActiveConnectionIfConnected()
    }

    /**
     * TPROXY support cannot change while the process lives, so it is probed exactly once here, at
     * application startup, and remembered for the rest of the lifecycle. Connecting, restarting the core
     * and opening the settings screen all read that cached verdict and never re-probe.
     *
     * Only root-mode users are probed, because the probe needs a root shell. Enabling root mode later
     * performs the one check at that point instead.
     */
    suspend fun loadRuntimeDiagnostics() = diagnosticsMutex.withLock {
        if (diagnosticsLoaded) return@withLock
        if (settingsRepository.useRootService.first() && checkRootAvailability()) {
            detectTproxyCompatibility()
        }
        _xrayCoreVersion.value = readXrayCoreVersion()
        diagnosticsLoaded = true
    }

    val tproxyCompatibility: StateFlow<TproxyCompatibility> get() = tproxyCompatibilityDetector.state

    suspend fun detectTproxyCompatibility(forceRefresh: Boolean = false): TproxyCompatibility {
        log.append(LogSource.APP, "Checking TPROXY IPv4 and IPv6 compatibility...")
        val detection = if (forceRefresh) {
            tproxyCompatibilityDetector.refresh()
        } else {
            tproxyCompatibilityDetector.detect()
        }
        return detection.also { result ->
            when (result) {
                is TproxyCompatibility.Supported -> log.append(
                    LogSource.APP,
                    "TPROXY compatibility: supported (ipv6=${result.ipv6})",
                )
                is TproxyCompatibility.Unsupported -> {
                    val details = result.details
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.take(1_000)
                        ?.takeIf(String::isNotEmpty)
                    log.append(
                        LogSource.APP,
                        "TPROXY compatibility: unsupported reason=${result.reason}" +
                            (details?.let { ", details=$it" } ?: ""),
                    )
                    demoteTproxyBackend(result)
                }
                TproxyCompatibility.Checking,
                TproxyCompatibility.Unknown,
                -> log.append(LogSource.APP, "TPROXY compatibility check returned $result")
            }
        }
    }

    /**
     * TPROXY is the default backend, so a device that cannot run it would otherwise fail at connect time
     * with the option greyed out and no way back. Moving the stored selection to TUN as soon as the
     * verdict is known keeps the next connect working without the user having to intervene.
     */
    private suspend fun demoteTproxyBackend(result: TproxyCompatibility.Unsupported) {
        if (!shouldDemoteTproxyBackend(result, settingsRepository.rootConnectionBackend.first())) return
        settingsRepository.setRootConnectionBackend(RootConnectionBackend.Tun)
        log.append(LogSource.APP, "TPROXY is unsupported on this device; the root backend was switched to TUN")
    }

    suspend fun updateGeoDataAsset(asset: GeoDataAsset, url: String) {
        when (asset) {
            GeoDataAsset.GEOIP -> settingsRepository.setGeoipUrl(url)
            GeoDataAsset.GEOSITE -> settingsRepository.setGeositeUrl(url)
        }
        geoDataManager.refresh(asset)
        reloadActiveConnectionIfConnected()
    }

    suspend fun checkRootAvailability(): Boolean {
        val available = withContext(Dispatchers.IO) { rootShell.open(RootShell.NetworkNamespace.INIT) }
        _rootAvailable.value = available
        if (!available && settingsRepository.useRootService.first()) {
            settingsRepository.setUseRootService(false)
            reloadActiveConnectionIfConnected()
        }
        return available
    }

    suspend fun readXrayCoreVersion(): String = withContext(Dispatchers.IO) {
        XrayBinary(context).readVersion() ?: "unknown"
    }

    private fun reloadActiveConnectionIfConnected() {
        if (stateCoordinator.state.value is ConnectionState.Connected) {
            XrayService.reload(context)
        }
    }
}

/**
 * Only a conclusive kernel verdict may rewrite the stored backend. A denied root shell, a timeout or a
 * foreign rule conflict says nothing about whether the device supports TPROXY, so those must leave the
 * user's choice alone and stay retryable.
 */
internal fun shouldDemoteTproxyBackend(
    result: TproxyCompatibility,
    currentBackend: RootConnectionBackend,
): Boolean = result is TproxyCompatibility.Unsupported &&
    result.isConclusive() &&
    currentBackend == RootConnectionBackend.Tproxy
