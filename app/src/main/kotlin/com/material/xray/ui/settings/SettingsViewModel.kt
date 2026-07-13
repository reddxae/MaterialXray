package com.material.xray.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.XrayBinary
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.repository.BackupManager
import com.material.xray.data.repository.BackupSummary
import com.material.xray.data.repository.PreparedBackupImport
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationStyle
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.service.AppUpdateChecker
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AssetUpdateMessage(
    @param:StringRes val messageResId: Int,
    val detail: String? = null,
)

data class BackupOperationMessage(
    @param:StringRes val messageResId: Int,
    val detail: String? = null,
)

@HiltViewModel
@Suppress("TooManyFunctions")
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val appUpdateChecker: AppUpdateChecker,
    private val appUpdateScheduler: AppUpdateScheduler,
    private val backupManager: BackupManager,
    private val database: AppDatabase,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val subscriptionRoutingRepository: SubscriptionRoutingRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val geoDataManager: GeoDataManager,
    private val launcherIconManager: LauncherIconManager,
    private val rootShell: RootShell,
) : ViewModel() {
    private val _geoipUpdating = MutableStateFlow(false)
    private val _geositeUpdating = MutableStateFlow(false)
    private val _assetUpdateEvents = MutableSharedFlow<AssetUpdateMessage>()
    private val _rootAccessDeniedEvents = MutableSharedFlow<Unit>()
    private val _databaseResetEvents = MutableSharedFlow<Boolean>()
    private val _databaseResetting = MutableStateFlow(false)
    private val _backupBusy = MutableStateFlow(false)
    private val _backupImportSummary = MutableStateFlow<BackupSummary?>(null)
    private val _backupEvents = MutableSharedFlow<BackupOperationMessage>()
    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    private val _xrayCoreVersion = MutableStateFlow<String?>(null)
    private var preparedBackupImport: PreparedBackupImport? = null

    val tunName = settingsRepo.tunName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "xray0")
    val dnsServers =
        settingsRepo.dnsServers.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsRepository.DEFAULT_DNS_SERVERS,
        )
    val domesticDnsServers =
        settingsRepo.domesticDnsServers.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsRepository.DEFAULT_DOMESTIC_DNS_SERVERS,
        )
    val latencyDnsServers =
        settingsRepo.latencyDnsServers.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsRepository.DEFAULT_LATENCY_DNS_SERVERS,
        )
    val autoConnect = settingsRepo.autoConnect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val useRootService = settingsRepo.useRootService.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val bypassLan = settingsRepo.bypassLan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val allowIpv6 = settingsRepo.allowIpv6.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val xrayBufferSizeKiB = settingsRepo.xrayBufferSizeKiB.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
    )
    val tunMtu = settingsRepo.tunMtu.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        XrayRuntimeSettings.DEFAULT_TUN_MTU,
    )
    val xrayMemoryRestartThresholdMiB = settingsRepo.xrayMemoryRestartThresholdMiB.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        XrayRuntimeSettings.DEFAULT_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
    )
    val xrayLogLevel = settingsRepo.xrayLogLevel.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        XrayLogLevel.default,
    )
    val defaultOutbound = settingsRepo.defaultOutbound.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        XrayOutbound.default,
    )
    val launcherIcon = settingsRepo.launcherIcon.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        LauncherIcon.default,
    )
    val showAdvancedOptions = settingsRepo.showAdvancedOptions.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false,
    )
    val notificationSettings = settingsRepo.notificationSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        com.material.xray.model.NotificationSettings(),
    )
    val subscriptionSendHardwareId = settingsRepo.subscriptionSendHardwareId.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true,
    )
    val routingPolicyControl = settingsRepo.routingPolicyControl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        RoutingPolicyControl.default,
    )
    val geoipUrl = settingsRepo.geoipUrl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsRepository.DEFAULT_GEOIP_URL,
    )
    val geositeUrl = settingsRepo.geositeUrl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsRepository.DEFAULT_GEOSITE_URL,
    )
    val latencyCheckUrl = settingsRepo.latencyCheckUrl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsRepository.DEFAULT_LATENCY_CHECK_URL,
    )
    val sortOutboundsByLatency = settingsRepo.sortOutboundsByLatency.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false,
    )
    val appUpdateChecksEnabled = settingsRepo.appUpdateChecksEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true,
    )
    val geoipUpdating: StateFlow<Boolean> = _geoipUpdating.asStateFlow()
    val geositeUpdating: StateFlow<Boolean> = _geositeUpdating.asStateFlow()
    val assetUpdateEvents: SharedFlow<AssetUpdateMessage> = _assetUpdateEvents.asSharedFlow()
    val rootAccessDeniedEvents: SharedFlow<Unit> = _rootAccessDeniedEvents.asSharedFlow()
    val databaseResetEvents: SharedFlow<Boolean> = _databaseResetEvents.asSharedFlow()
    val databaseResetting: StateFlow<Boolean> = _databaseResetting.asStateFlow()
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()
    val backupImportSummary: StateFlow<BackupSummary?> = _backupImportSummary.asStateFlow()
    val backupEvents: SharedFlow<BackupOperationMessage> = _backupEvents.asSharedFlow()
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()
    val xrayCoreVersion: StateFlow<String?> = _xrayCoreVersion.asStateFlow()

    init {
        checkRootAvailability()
        loadXrayCoreVersion()
    }

    fun setTunName(name: String) = updateXrayConfigStringSetting(name, tunName.value, settingsRepo::setTunName)
    fun setDnsServers(servers: String) = updateXrayConfigStringSetting(servers, dnsServers.value, settingsRepo::setDnsServers)
    fun setDomesticDnsServers(servers: String) = updateXrayConfigStringSetting(servers, domesticDnsServers.value, settingsRepo::setDomesticDnsServers)
    fun setLatencyDnsServers(servers: String) = viewModelScope.launch { settingsRepo.setLatencyDnsServers(servers) }
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(enabled) }
    fun setUseRootService(enabled: Boolean) = viewModelScope.launch {
        if (enabled == useRootService.value) return@launch
        if (!enabled) {
            settingsRepo.setUseRootService(false)
            reloadActiveConnectionIfConnected()
            return@launch
        }

        if (_rootAvailable.value == false) {
            _rootAccessDeniedEvents.emit(Unit)
            return@launch
        }

        val rootAvailable = withContext(Dispatchers.IO) { rootShell.open() }
        if (!rootAvailable) {
            _rootAvailable.value = false
            _rootAccessDeniedEvents.emit(Unit)
            return@launch
        }

        _rootAvailable.value = true
        settingsRepo.setUseRootService(true)
        reloadActiveConnectionIfConnected()
    }
    fun setBypassLan(enabled: Boolean) = viewModelScope.launch {
        if (enabled == bypassLan.value) return@launch
        settingsRepo.setBypassLan(enabled)
        reloadActiveConnectionIfConnected()
    }
    fun setAllowIpv6(enabled: Boolean) = viewModelScope.launch {
        if (enabled == allowIpv6.value) return@launch
        settingsRepo.setAllowIpv6(enabled)
        reloadActiveConnectionIfConnected()
    }
    fun setXrayBufferSizeKiB(bufferSizeKiB: Int) = viewModelScope.launch {
        if (bufferSizeKiB == xrayBufferSizeKiB.value || !XrayRuntimeSettings.isValidXrayBufferSizeKiB(bufferSizeKiB)) {
            return@launch
        }
        settingsRepo.setXrayBufferSizeKiB(bufferSizeKiB)
        reloadActiveConnectionIfConnected()
    }
    fun setTunMtu(mtu: Int) = viewModelScope.launch {
        if (mtu == tunMtu.value || !XrayRuntimeSettings.isValidTunMtu(mtu)) return@launch
        settingsRepo.setTunMtu(mtu)
        reloadActiveConnectionIfConnected()
    }
    fun setXrayMemoryRestartThresholdMiB(thresholdMiB: Int) = viewModelScope.launch {
        if (
            thresholdMiB == xrayMemoryRestartThresholdMiB.value ||
            !XrayRuntimeSettings.isValidXrayMemoryRestartThresholdMiB(thresholdMiB)
        ) {
            return@launch
        }
        settingsRepo.setXrayMemoryRestartThresholdMiB(thresholdMiB)
    }
    fun setXrayLogLevel(level: XrayLogLevel) = viewModelScope.launch {
        if (level == xrayLogLevel.value) return@launch
        settingsRepo.setXrayLogLevel(level)
        reloadActiveConnectionIfConnected()
    }
    fun setDefaultOutbound(outbound: XrayOutbound) = viewModelScope.launch {
        if (outbound == defaultOutbound.value) return@launch
        settingsRepo.setDefaultOutbound(outbound)
        reloadActiveConnectionIfConnected()
    }
    fun setLauncherIcon(icon: LauncherIcon) = viewModelScope.launch {
        if (icon == launcherIcon.value) return@launch
        settingsRepo.setLauncherIcon(icon)
        launcherIconManager.apply(icon)
    }
    fun setShowAdvancedOptions(enabled: Boolean) = viewModelScope.launch {
        if (enabled == showAdvancedOptions.value) return@launch
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
    fun setNotificationFieldEnabled(field: NotificationField, enabled: Boolean) = when (field) {
        NotificationField.TrafficSpeed -> setNotificationShowTrafficSpeed(enabled)
        NotificationField.RamUsage -> setNotificationShowRamUsage(enabled)
        NotificationField.ConnectionCount -> setNotificationShowConnectionCount(enabled)
    }
    fun setNotificationFieldOrder(order: List<NotificationField>) = viewModelScope.launch {
        settingsRepo.setNotificationFieldOrder(order)
    }

    fun setSubscriptionSendHardwareId(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setSubscriptionSendHardwareId(enabled)
    }
    fun setAppUpdateChecksEnabled(enabled: Boolean) = viewModelScope.launch {
        if (enabled == appUpdateChecksEnabled.value) return@launch
        settingsRepo.setAppUpdateChecksEnabled(enabled)
        appUpdateScheduler.setEnabled(enabled)
    }
    fun checkForAppUpdate() = viewModelScope.launch {
        try {
            appUpdateChecker.check(manual = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Manual checks also fail silently; the user can try again later.
        }
    }
    fun setRoutingPolicyControl(policy: RoutingPolicyControl) = viewModelScope.launch {
        if (policy == routingPolicyControl.value) return@launch
        settingsRepo.setRoutingPolicyControl(policy)
        if (policy == RoutingPolicyControl.SubscriptionProvider) {
            val appRoutingChanged = subscriptionAppRoutingRepository.applyForSelectedServerIfProviderControlled()
            val routingChanged = subscriptionRoutingRepository.applyForSelectedServerIfProviderControlled()
            if (!appRoutingChanged && !routingChanged) return@launch
            if (connectionStateCoordinator.state.value is ConnectionState.Connected) {
                routingChangeManager.markPendingChanges(
                    if (routingChanged) PendingRoutingChange.XRAY_CONFIG else PendingRoutingChange.APP_ROUTING,
                )
            }
            reloadActiveConnectionIfConnected()
        }
    }

    fun setGeoipUrl(url: String) = viewModelScope.launch { settingsRepo.setGeoipUrl(url) }
    fun setGeositeUrl(url: String) = viewModelScope.launch { settingsRepo.setGeositeUrl(url) }
    fun setLatencyCheckUrl(url: String) = viewModelScope.launch { settingsRepo.setLatencyCheckUrl(url) }
    fun setSortOutboundsByLatency(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setSortOutboundsByLatency(enabled)
    }

    fun resetInternalDatabase() {
        if (_databaseResetting.value) return
        viewModelScope.launch {
            _databaseResetting.value = true
            try {
                val result = runCatching {
                    if (connectionStateCoordinator.state.value.requiresDisconnectForDatabaseReset()) {
                        XrayService.disconnect(context, force = true)
                        check(
                            withTimeoutOrNull(DATABASE_RESET_DISCONNECT_TIMEOUT_MILLIS) {
                                connectionStateCoordinator.state.first { !it.requiresDisconnectForDatabaseReset() }
                            } != null,
                        ) { "Timed out waiting for the active connection to stop" }
                    }
                    settingsRepo.setLastServerId(-1)
                    routingChangeManager.clearPendingChanges()
                    withContext(Dispatchers.IO) {
                        database.clearAllTables()
                    }
                }
                result.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                    Log.e(LOG_TAG, "Unable to reset internal database", error)
                }
                _databaseResetEvents.emit(result.isSuccess)
            } finally {
                _databaseResetting.value = false
            }
        }
    }

    fun updateGeoipAsset(url: String) {
        updateGeoDataAsset(
            asset = GeoDataAsset.GEOIP,
            url = url,
            setUrl = settingsRepo::setGeoipUrl,
            updating = _geoipUpdating,
            successMessageResId = R.string.settings_geoip_updated,
        )
    }

    fun updateGeositeAsset(url: String) {
        updateGeoDataAsset(
            asset = GeoDataAsset.GEOSITE,
            url = url,
            setUrl = settingsRepo::setGeositeUrl,
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
            _backupEvents.emit(
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
                _backupEvents.emit(
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
                _backupEvents.emit(BackupOperationMessage(R.string.settings_backup_imported))
            } else {
                _backupEvents.emit(
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
        setUrl: suspend (String) -> Unit,
        updating: MutableStateFlow<Boolean>,
        @StringRes successMessageResId: Int,
    ) {
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            runCatching {
                setUrl(url)
                geoDataManager.refresh(asset)
            }.onSuccess {
                _assetUpdateEvents.emit(AssetUpdateMessage(successMessageResId))
                reloadActiveConnectionIfConnected()
            }.onFailure { error ->
                _assetUpdateEvents.emit(
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

    private fun checkRootAvailability() {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { rootShell.open() }
            _rootAvailable.value = available
            if (!available && useRootService.value) {
                settingsRepo.setUseRootService(false)
                reloadActiveConnectionIfConnected()
            }
        }
    }

    private fun loadXrayCoreVersion() {
        viewModelScope.launch {
            _xrayCoreVersion.value = withContext(Dispatchers.IO) {
                XrayBinary(context).readVersion() ?: "unknown"
            }
        }
    }
}

private fun ConnectionState.requiresDisconnectForDatabaseReset(): Boolean = when (this) {
    ConnectionState.Connecting,
    ConnectionState.ApplyingRoutingChanges,
    ConnectionState.UpdatingRoutingData,
    is ConnectionState.Connected,
    ConnectionState.Disconnecting,
    -> true

    ConnectionState.Disconnected,
    is ConnectionState.Error,
    is ConnectionState.InterfaceBusy,
    is ConnectionState.RestartRequired,
    -> false
}

private const val DATABASE_RESET_DISCONNECT_TIMEOUT_MILLIS = 10_000L
private const val LOG_TAG = "SettingsViewModel"
