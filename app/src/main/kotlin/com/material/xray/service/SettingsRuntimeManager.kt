package com.material.xray.service

import android.content.Context
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.XrayBinary
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.LauncherIcon
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
) {
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
        if (!available) return false
        settingsRepository.setUseRootService(true)
        reloadActiveConnectionIfConnected()
        return true
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
