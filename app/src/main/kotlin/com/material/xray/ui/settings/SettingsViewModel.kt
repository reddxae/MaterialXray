package com.material.xray.ui.settings

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.data.repository.BackupManager
import com.material.xray.data.repository.BackupSummary
import com.material.xray.data.repository.PreparedBackupImport
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.AppUpdateCheckStatus
import com.material.xray.model.ConnectionState
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationStyle
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.model.isInProgress
import com.material.xray.model.normalizeDnsServersForIpv6
import com.material.xray.service.AppUpdateChecker
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.DatabaseResetManager
import com.material.xray.service.SettingsRuntimeManager
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AssetUpdateMessage(
    @param:StringRes val messageResId: Int,
    val detail: String? = null,
)

data class BackupOperationMessage(
    @param:StringRes val messageResId: Int,
    val detail: String? = null,
)

private const val APP_UPDATE_CHECK_STATUS_MINIMUM_DURATION_MILLIS = 750L

@HiltViewModel
@Suppress("TooManyFunctions")
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val appUpdateChecker: AppUpdateChecker,
    private val backupManager: BackupManager,
    private val databaseResetManager: DatabaseResetManager,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val providerRoutingCoordinator: ProviderRoutingCoordinator,
    private val settingsRuntimeManager: SettingsRuntimeManager,
    settingsDataState: SettingsDataState,
) : ViewModel() {
    private val _geoipUpdating = MutableStateFlow(false)
    private val _geositeUpdating = MutableStateFlow(false)
    private val _assetUpdateEvents = Channel<AssetUpdateMessage>(Channel.BUFFERED)
    private val _rootAccessDeniedEvents = Channel<Unit>(Channel.BUFFERED)
    private val _databaseResetEvents = Channel<Boolean>(Channel.BUFFERED)
    private val _databaseResetting = MutableStateFlow(false)
    private val _backupBusy = MutableStateFlow(false)
    private val _backupImportSummary = MutableStateFlow<BackupSummary?>(null)
    private val _backupEvents = Channel<BackupOperationMessage>(Channel.BUFFERED)
    private val _appUpdateCheckStatus = MutableStateFlow<AppUpdateCheckStatus?>(null)
    private var preparedBackupImport: PreparedBackupImport? = null

    val settings = settingsDataState.data
    val geoipUpdating: StateFlow<Boolean> = _geoipUpdating.asStateFlow()
    val geositeUpdating: StateFlow<Boolean> = _geositeUpdating.asStateFlow()
    val assetUpdateEvents: Flow<AssetUpdateMessage> = _assetUpdateEvents.receiveAsFlow()
    val rootAccessDeniedEvents: Flow<Unit> = _rootAccessDeniedEvents.receiveAsFlow()
    val databaseResetEvents: Flow<Boolean> = _databaseResetEvents.receiveAsFlow()
    val databaseResetting: StateFlow<Boolean> = _databaseResetting.asStateFlow()
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()
    val backupImportSummary: StateFlow<BackupSummary?> = _backupImportSummary.asStateFlow()
    val backupEvents: Flow<BackupOperationMessage> = _backupEvents.receiveAsFlow()
    val rootAvailable: StateFlow<Boolean?> = settingsRuntimeManager.rootAvailable
    val tproxyCompatibility: StateFlow<TproxyCompatibility> = settingsRuntimeManager.tproxyCompatibility
    val xrayCoreVersion: StateFlow<String?> = settingsRuntimeManager.xrayCoreVersion
    val startupReady: StateFlow<Boolean> = settingsRuntimeManager.startupReady
    val appUpdateCheckStatus: StateFlow<AppUpdateCheckStatus?> = _appUpdateCheckStatus.asStateFlow()

    fun setTunName(name: String) = updateXrayConfigStringSetting(name, currentSettings().tunName, settingsRepo::setTunName)
    fun normalizeDnsServers(servers: String): String = normalizeDnsServersForIpv6(servers, currentSettings().allowIpv6)

    fun setDnsServers(servers: String) = updateXrayConfigStringSetting(
        normalizeDnsServers(servers),
        currentSettings().dnsServers,
        settingsRepo::setDnsServers,
    )
    fun setDomesticDnsServers(servers: String) = updateXrayConfigStringSetting(
        normalizeDnsServers(servers),
        currentSettings().domesticDnsServers,
        settingsRepo::setDomesticDnsServers,
    )
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(enabled) }
    fun setUseRootService(enabled: Boolean) = viewModelScope.launch {
        if (enabled == currentSettings().useRootService) return@launch
        if (!enabled) {
            settingsRuntimeManager.setUseRootService(false)
            return@launch
        }

        if (rootAvailable.value == false) {
            _rootAccessDeniedEvents.send(Unit)
            return@launch
        }

        val rootAvailable = settingsRuntimeManager.setUseRootService(true)
        if (!rootAvailable) {
            _rootAccessDeniedEvents.send(Unit)
            return@launch
        }

        checkTproxyCompatibility()
    }
    fun setRootConnectionBackend(backend: RootConnectionBackend) = viewModelScope.launch {
        if (backend == currentSettings().rootConnectionBackend) return@launch
        if (backend == RootConnectionBackend.Tproxy && tproxyCompatibility.value is TproxyCompatibility.Unsupported) {
            return@launch
        }
        settingsRuntimeManager.setRootConnectionBackend(backend)
    }

    fun retryTproxyCompatibilityCheck() = checkTproxyCompatibility(forceRefresh = true)
    fun setBypassLan(enabled: Boolean) = viewModelScope.launch {
        if (enabled == currentSettings().bypassLan) return@launch
        settingsRepo.setBypassLan(enabled)
        reloadActiveConnectionIfConnected()
    }
    fun setAllowIpv6(enabled: Boolean) = viewModelScope.launch {
        val settings = currentSettings()
        if (enabled == settings.allowIpv6) return@launch
        if (enabled && !isIpv6SelectionEnabled(settings.useRootService, settings.rootConnectionBackend, tproxyCompatibility.value)) {
            return@launch
        }
        settingsRepo.setAllowIpv6(enabled)
        reloadActiveConnectionIfConnected()
    }
    fun setXrayBufferSizeKiB(bufferSizeKiB: Int) = viewModelScope.launch {
        if (
            bufferSizeKiB == currentSettings().xrayBufferSizeKiB ||
            !XrayRuntimeSettings.isValidXrayBufferSizeKiB(bufferSizeKiB)
        ) {
            return@launch
        }
        settingsRepo.setXrayBufferSizeKiB(bufferSizeKiB)
        reloadActiveConnectionIfConnected()
    }
    fun setTunMtu(mtu: Int) = viewModelScope.launch {
        if (mtu == currentSettings().tunMtu || !XrayRuntimeSettings.isValidTunMtu(mtu)) return@launch
        settingsRepo.setTunMtu(mtu)
        reloadActiveConnectionIfConnected()
    }
    fun setXrayMemoryRestartThresholdMiB(thresholdMiB: Int) = viewModelScope.launch {
        if (
            thresholdMiB == currentSettings().xrayMemoryRestartThresholdMiB ||
            !XrayRuntimeSettings.isValidXrayMemoryRestartThresholdMiB(thresholdMiB)
        ) {
            return@launch
        }
        settingsRepo.setXrayMemoryRestartThresholdMiB(thresholdMiB)
    }
    fun setPassiveHealthMonitoringEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled == currentSettings().passiveHealthMonitoringEnabled) return@launch
        settingsRepo.setPassiveHealthMonitoringEnabled(enabled)
    }
    fun setXrayLogLevel(level: XrayLogLevel) = viewModelScope.launch {
        if (level == currentSettings().xrayLogLevel) return@launch
        settingsRepo.setXrayLogLevel(level)
        reloadActiveConnectionIfConnected()
    }
    fun setDefaultOutbound(outbound: XrayOutbound) = viewModelScope.launch {
        if (outbound == currentSettings().defaultOutbound) return@launch
        settingsRepo.setDefaultOutbound(outbound)
        reloadActiveConnectionIfConnected()
    }
    fun setLauncherIcon(icon: LauncherIcon) = viewModelScope.launch {
        if (icon == currentSettings().launcherIcon) return@launch
        settingsRuntimeManager.setLauncherIcon(icon)
    }
    fun setShowTitleBarLogo(enabled: Boolean) = viewModelScope.launch {
        if (enabled == currentSettings().showTitleBarLogo) return@launch
        settingsRepo.setShowTitleBarLogo(enabled)
    }
    fun setShowAdvancedOptions(enabled: Boolean) = viewModelScope.launch {
        if (enabled == currentSettings().showAdvancedOptions) return@launch
        settingsRepo.setShowAdvancedOptions(enabled)
        reloadActiveConnectionIfConnected()
    }

    fun setNotificationEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationEnabled(enabled)
    }
    fun setNotificationUpdateIntervalMs(intervalMs: Int) = viewModelScope.launch {
        settingsRepo.setNotificationUpdateIntervalMs(intervalMs)
    }
    fun setNotificationStyle(style: NotificationStyle) = viewModelScope.launch {
        settingsRepo.setNotificationStyle(style)
    }
    fun setNotificationShowTrafficSpeed(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationShowTrafficSpeed(enabled)
    }
    fun setNotificationShowRamUsage(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationShowRamUsage(enabled)
    }
    fun setNotificationShowConnectionCount(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationShowConnectionCount(enabled)
    }
    fun setNotificationShowPing(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationShowPing(enabled)
    }
    fun setNotificationShowSessionTraffic(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNotificationShowSessionTraffic(enabled)
    }
    fun setNotificationFieldEnabled(field: NotificationField, enabled: Boolean) = when (field) {
        NotificationField.TrafficSpeed -> setNotificationShowTrafficSpeed(enabled)
        NotificationField.RamUsage -> setNotificationShowRamUsage(enabled)
        NotificationField.ConnectionCount -> setNotificationShowConnectionCount(enabled)
        NotificationField.Ping -> setNotificationShowPing(enabled)
        NotificationField.SessionTraffic -> setNotificationShowSessionTraffic(enabled)
    }
    fun setNotificationFieldOrder(order: List<NotificationField>) = viewModelScope.launch {
        settingsRepo.setNotificationFieldOrder(order)
    }

    fun setSubscriptionSendHardwareId(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setSubscriptionSendHardwareId(enabled)
    }
    fun setAppUpdateChecksEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled == currentSettings().appUpdateChecksEnabled) return@launch
        settingsRuntimeManager.setAppUpdateChecksEnabled(enabled)
    }
    fun checkForAppUpdate() {
        if (_appUpdateCheckStatus.value?.isInProgress == true) return
        _appUpdateCheckStatus.value = AppUpdateCheckStatus.Starting
        viewModelScope.launch {
            var statusShownAtMillis: Long? = null
            val showStatus: suspend (AppUpdateCheckStatus) -> Unit = { status ->
                statusShownAtMillis?.let { shownAtMillis ->
                    val elapsedMillis = SystemClock.elapsedRealtime() - shownAtMillis
                    delay((APP_UPDATE_CHECK_STATUS_MINIMUM_DURATION_MILLIS - elapsedMillis).coerceAtLeast(0L))
                }
                _appUpdateCheckStatus.value = status
                statusShownAtMillis = SystemClock.elapsedRealtime()
            }
            try {
                val update = appUpdateChecker.check(manual = true, onStatus = showStatus)
                showStatus(
                    update?.let {
                        AppUpdateCheckStatus.UpdateAvailable(it.tagName)
                    } ?: AppUpdateCheckStatus.UpToDate,
                )
            } catch (error: CancellationException) {
                _appUpdateCheckStatus.value = null
                throw error
            } catch (_: Exception) {
                showStatus(AppUpdateCheckStatus.Failed)
            }
        }
    }
    fun setRoutingPolicyControl(policy: RoutingPolicyControl) = viewModelScope.launch {
        if (policy == currentSettings().routingPolicyControl) return@launch
        settingsRepo.setRoutingPolicyControl(policy)
        if (policy == RoutingPolicyControl.SubscriptionProvider) {
            providerRoutingCoordinator.refreshSelectedServer()
        }
    }

    fun setGeoipUrl(url: String) = viewModelScope.launch { settingsRepo.setGeoipUrl(url) }
    fun setGeositeUrl(url: String) = viewModelScope.launch { settingsRepo.setGeositeUrl(url) }
    fun setLatencyCheckUrl(url: String) = viewModelScope.launch { settingsRepo.setLatencyCheckUrl(url) }
    fun setSortOutboundsByLatency(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setSortOutboundsByLatency(enabled)
    }
    fun setShowBothLatencyResults(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setShowBothLatencyResults(enabled)
    }

    fun resetInternalDatabase() {
        if (_databaseResetting.value) return
        viewModelScope.launch {
            _databaseResetting.value = true
            try {
                val result = runCatching {
                    databaseResetManager.reset()
                }
                result.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                _databaseResetEvents.send(result.isSuccess)
            } finally {
                _databaseResetting.value = false
            }
        }
    }

    fun updateGeoipAsset(url: String) {
        updateGeoDataAsset(
            asset = GeoDataAsset.GEOIP,
            url = url,
            updating = _geoipUpdating,
            successMessageResId = R.string.settings_geoip_updated,
        )
    }

    fun updateGeositeAsset(url: String) {
        updateGeoDataAsset(
            asset = GeoDataAsset.GEOSITE,
            url = url,
            updating = _geositeUpdating,
            successMessageResId = R.string.settings_geosite_updated,
        )
    }

    fun exportBackup(uri: Uri) {
        if (_backupBusy.value) return
        viewModelScope.launch {
            _backupBusy.value = true
            val result = runCatching { withContext(Dispatchers.IO) { backupManager.export(uri) } }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            _backupEvents.send(
                if (result.isSuccess) {
                    BackupOperationMessage(R.string.settings_backup_exported)
                } else {
                    backupFailureMessage(
                        defaultMessageResId = R.string.settings_backup_export_failed,
                        detailedMessageResId = R.string.settings_backup_export_failed_with_detail,
                        error = requireNotNull(result.exceptionOrNull()),
                    )
                },
            )
            _backupBusy.value = false
        }
    }

    fun prepareBackupImport(uri: Uri) {
        if (_backupBusy.value) return
        viewModelScope.launch {
            _backupBusy.value = true
            val result = runCatching { withContext(Dispatchers.IO) { backupManager.prepareImport(uri) } }
            result.onSuccess { prepared ->
                preparedBackupImport = prepared
                _backupImportSummary.value = prepared.summary
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _backupEvents.send(
                    backupFailureMessage(
                        defaultMessageResId = R.string.settings_backup_import_failed,
                        detailedMessageResId = R.string.settings_backup_import_failed_with_detail,
                        error = error,
                    ),
                )
            }
            _backupBusy.value = false
        }
    }

    fun dismissBackupImport() {
        if (_backupBusy.value) return
        preparedBackupImport = null
        _backupImportSummary.value = null
    }

    fun confirmBackupImport() {
        val prepared = preparedBackupImport ?: return
        if (_backupBusy.value) return
        viewModelScope.launch {
            _backupBusy.value = true
            val result = runCatching { withContext(Dispatchers.IO) { backupManager.restore(prepared) } }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            if (result.isSuccess) {
                preparedBackupImport = null
                _backupImportSummary.value = null
                _backupEvents.send(BackupOperationMessage(R.string.settings_backup_imported))
            } else {
                _backupEvents.send(
                    backupFailureMessage(
                        defaultMessageResId = R.string.settings_backup_import_failed,
                        detailedMessageResId = R.string.settings_backup_import_failed_with_detail,
                        error = requireNotNull(result.exceptionOrNull()),
                    ),
                )
            }
            _backupBusy.value = false
        }
    }

    private fun backupFailureMessage(
        @StringRes defaultMessageResId: Int,
        @StringRes detailedMessageResId: Int,
        error: Throwable,
    ): BackupOperationMessage = error.message?.takeIf { it.isNotBlank() }?.let { detail ->
        BackupOperationMessage(detailedMessageResId, detail)
    } ?: BackupOperationMessage(defaultMessageResId)

    private fun updateGeoDataAsset(
        asset: GeoDataAsset,
        url: String,
        updating: MutableStateFlow<Boolean>,
        @StringRes successMessageResId: Int,
    ) {
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            runCatching {
                settingsRuntimeManager.updateGeoDataAsset(asset, url)
            }.onSuccess {
                _assetUpdateEvents.send(AssetUpdateMessage(successMessageResId))
            }.onFailure { error ->
                _assetUpdateEvents.send(
                    error.message?.let { detail ->
                        AssetUpdateMessage(R.string.settings_asset_update_failed_with_detail, detail)
                    } ?: AssetUpdateMessage(R.string.settings_asset_update_failed),
                )
            }
            updating.value = false
        }
    }

    private fun updateXrayConfigStringSetting(
        newValue: String,
        currentValue: String,
        setter: suspend (String) -> Unit,
    ) {
        viewModelScope.launch {
            val trimmedValue = newValue.trim()
            if (trimmedValue == currentValue) return@launch
            setter(trimmedValue)
            reloadActiveConnectionIfConnected()
        }
    }

    private fun reloadActiveConnectionIfConnected() {
        if (connectionStateCoordinator.state.value is ConnectionState.Connected) {
            XrayService.reload(context)
        }
    }

    private fun checkTproxyCompatibility(forceRefresh: Boolean = false) {
        if (tproxyCompatibility.value == TproxyCompatibility.Checking) return
        viewModelScope.launch {
            settingsRuntimeManager.detectTproxyCompatibility(forceRefresh)
        }
    }

    private fun currentSettings() = checkNotNull(settings.value) { "Settings are not loaded" }
}

internal fun isIpv6SelectionEnabled(
    rootServiceActive: Boolean,
    backend: RootConnectionBackend,
    compatibility: TproxyCompatibility,
): Boolean = !(
    rootServiceActive &&
        backend == RootConnectionBackend.Tproxy &&
        compatibility is TproxyCompatibility.Supported &&
        !compatibility.ipv6
    )
