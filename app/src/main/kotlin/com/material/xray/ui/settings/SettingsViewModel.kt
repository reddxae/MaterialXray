package com.material.xray.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.app.appKey
import com.material.xray.core.app.parseAppKey
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.XrayBinary
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.data.repository.toSubscriptionAppRouting
import com.material.xray.data.repository.toSubscriptionMetadata
import com.material.xray.data.repository.toSubscriptionRouting
import com.material.xray.data.repository.withSubscriptionAppRouting
import com.material.xray.data.repository.withSubscriptionMetadata
import com.material.xray.data.repository.withSubscriptionRouting
import com.material.xray.model.BackupData
import com.material.xray.model.ConnectionState
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationStyle
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.service.ConnectionStateHolder
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AssetUpdateMessage(
    @param:StringRes val messageResId: Int,
    val detail: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val appBypassDao: AppBypassDao,
    private val database: AppDatabase,
    private val connectionStateHolder: ConnectionStateHolder,
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val subscriptionRoutingRepository: SubscriptionRoutingRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val geoDataManager: GeoDataManager,
    private val launcherIconManager: LauncherIconManager,
    private val rootShell: RootShell,
) : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val _geoipUpdating = MutableStateFlow(false)
    private val _geositeUpdating = MutableStateFlow(false)
    private val _assetUpdateEvents = MutableSharedFlow<AssetUpdateMessage>()
    private val _rootAccessDeniedEvents = MutableSharedFlow<Unit>()
    private val _databaseResetEvents = MutableSharedFlow<Boolean>()
    private val _databaseResetting = MutableStateFlow(false)
    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    private val _xrayCoreVersion = MutableStateFlow<String?>(null)

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
    val geoipUpdating: StateFlow<Boolean> = _geoipUpdating.asStateFlow()
    val geositeUpdating: StateFlow<Boolean> = _geositeUpdating.asStateFlow()
    val assetUpdateEvents: SharedFlow<AssetUpdateMessage> = _assetUpdateEvents.asSharedFlow()
    val rootAccessDeniedEvents: SharedFlow<Unit> = _rootAccessDeniedEvents.asSharedFlow()
    val databaseResetEvents: SharedFlow<Boolean> = _databaseResetEvents.asSharedFlow()
    val databaseResetting: StateFlow<Boolean> = _databaseResetting.asStateFlow()
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
    fun setRoutingPolicyControl(policy: RoutingPolicyControl) = viewModelScope.launch {
        if (policy == routingPolicyControl.value) return@launch
        settingsRepo.setRoutingPolicyControl(policy)
        if (policy == RoutingPolicyControl.SubscriptionProvider) {
            val appRoutingChanged = subscriptionAppRoutingRepository.applyForSelectedServerIfProviderControlled()
            val routingChanged = subscriptionRoutingRepository.applyForSelectedServerIfProviderControlled()
            if (!appRoutingChanged && !routingChanged) return@launch
            if (connectionStateHolder.state.value is ConnectionState.Connected) {
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

    fun resetInternalDatabase() {
        if (_databaseResetting.value) return
        viewModelScope.launch {
            _databaseResetting.value = true
            try {
                val result = runCatching {
                    if (connectionStateHolder.state.value.requiresDisconnectForDatabaseReset()) {
                        XrayService.disconnect(context)
                        check(
                            withTimeoutOrNull(DATABASE_RESET_DISCONNECT_TIMEOUT_MILLIS) {
                                connectionStateHolder.state.first { !it.requiresDisconnectForDatabaseReset() }
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val subs = subscriptionDao.getAll()
                val bypassed = appBypassDao.getExcluded().map {
                    if (it.profileId == 0) it.packageName else appKey(it.profileId, it.packageName)
                }
                val settings = settingsRepo.getAllAsMap()

                val backup = BackupData(
                    subscriptions = subs.map { sub ->
                        BackupData.BackupSubscription(
                            name = sub.name,
                            url = sub.url,
                            preferJson = sub.preferJson,
                            autoUpdateIntervalHours = sub.autoUpdateIntervalHours,
                            descriptionHidden = sub.descriptionHidden,
                            userAgentMode = sub.userAgentMode,
                            customUserAgent = sub.customUserAgent,
                            customHeaders = sub.customHeaders,
                            metadata = sub.toSubscriptionMetadata(),
                            appRouting = sub.toSubscriptionAppRouting(),
                            routing = sub.toSubscriptionRouting(),
                        )
                    },
                    bypassedApps = bypassed,
                    settings = settings,
                )

                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.encodeToString(backup).toByteArray())
                }
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(uri)
                    ?.use { it.bufferedReader().readText() }
                    ?: return@withContext
                val backup = runCatching { json.decodeFromString<BackupData>(text) }.getOrNull() ?: return@withContext

                subscriptionDao.deleteAll()
                serverDao.deleteAll()
                appBypassDao.deleteAll()

                backup.subscriptions.forEach { sub ->
                    subscriptionDao.insert(
                        SubscriptionEntity(
                            name = sub.name,
                            url = sub.url,
                            preferJson = sub.preferJson,
                            autoUpdateIntervalHours = sub.autoUpdateIntervalHours,
                            descriptionHidden = sub.descriptionHidden,
                            userAgentMode = sub.userAgentMode,
                            customUserAgent = sub.customUserAgent,
                            customHeaders = sub.customHeaders,
                        ).withSubscriptionMetadata(sub.metadata)
                            .withSubscriptionAppRouting(sub.appRouting)
                            .withSubscriptionRouting(sub.routing),
                    )
                }
                backup.bypassedApps.forEach { value ->
                    val app = parseAppKey(value)
                    appBypassDao.upsert(
                        AppBypassEntity(
                            packageName = app.packageName,
                            profileId = app.profileId,
                            uid = 0,
                            excluded = true,
                        ),
                    )
                }
                settingsRepo.restoreFromMap(backup.settings)
                launcherIconManager.apply(settingsRepo.launcherIcon.first())
                reloadActiveConnectionIfConnected()
            }
        }
    }

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
        if (connectionStateHolder.state.value is ConnectionState.Connected) {
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
