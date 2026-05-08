package com.material.xray.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.core.app.appKey
import com.material.xray.core.app.parseAppKey
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.toSubscriptionMetadata
import com.material.xray.data.repository.withSubscriptionMetadata
import com.material.xray.model.BackupData
import com.material.xray.model.ConnectionState
import com.material.xray.model.LauncherIcon
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.service.ConnectionStateHolder
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val appBypassDao: AppBypassDao,
    private val connectionStateHolder: ConnectionStateHolder,
    private val geoDataManager: GeoDataManager,
    private val launcherIconManager: LauncherIconManager,
    private val rootShell: RootShell,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _geoipUpdating = MutableStateFlow(false)
    private val _geositeUpdating = MutableStateFlow(false)
    private val _assetUpdateEvents = MutableSharedFlow<String>()
    private val _rootAccessDeniedEvents = MutableSharedFlow<Unit>()

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
    val assetUpdateEvents: SharedFlow<String> = _assetUpdateEvents.asSharedFlow()
    val rootAccessDeniedEvents: SharedFlow<Unit> = _rootAccessDeniedEvents.asSharedFlow()

    fun setTunName(name: String) = updateXrayConfigStringSetting(name, tunName.value, settingsRepo::setTunName)
    fun setDnsServers(servers: String) =
        updateXrayConfigStringSetting(servers, dnsServers.value, settingsRepo::setDnsServers)
    fun setDomesticDnsServers(servers: String) =
        updateXrayConfigStringSetting(servers, domesticDnsServers.value, settingsRepo::setDomesticDnsServers)
    fun setLatencyDnsServers(servers: String) = viewModelScope.launch { settingsRepo.setLatencyDnsServers(servers) }
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(enabled) }
    fun setUseRootService(enabled: Boolean) = viewModelScope.launch {
        if (enabled == useRootService.value) return@launch
        if (!enabled) {
            settingsRepo.setUseRootService(false)
            reloadActiveConnectionIfConnected()
            return@launch
        }

        val rootAvailable = withContext(Dispatchers.IO) { rootShell.open() }
        if (!rootAvailable) {
            _rootAccessDeniedEvents.emit(Unit)
            return@launch
        }

        settingsRepo.setUseRootService(true)
        reloadActiveConnectionIfConnected()
    }
    fun setBypassLan(enabled: Boolean) = viewModelScope.launch {
        if (enabled == bypassLan.value) return@launch
        settingsRepo.setBypassLan(enabled)
        reloadActiveConnectionIfConnected()
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

    fun setGeoipUrl(url: String) = viewModelScope.launch { settingsRepo.setGeoipUrl(url) }
    fun setGeositeUrl(url: String) = viewModelScope.launch { settingsRepo.setGeositeUrl(url) }
    fun setLatencyCheckUrl(url: String) = viewModelScope.launch { settingsRepo.setLatencyCheckUrl(url) }

    fun updateGeoipAsset(url: String) {
        updateGeoDataAsset(
            asset = GeoDataAsset.GEOIP,
            url = url,
            setUrl = settingsRepo::setGeoipUrl,
            updating = _geoipUpdating,
            successMessage = "GeoIP updated",
        )
    }

    fun updateGeositeAsset(url: String) {
        updateGeoDataAsset(
            asset = GeoDataAsset.GEOSITE,
            url = url,
            setUrl = settingsRepo::setGeositeUrl,
            updating = _geositeUpdating,
            successMessage = "GeoSite updated",
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
                            autoUpdateIntervalHours = sub.autoUpdateIntervalHours,
                            descriptionHidden = sub.descriptionHidden,
                            metadata = sub.toSubscriptionMetadata(),
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
                            autoUpdateIntervalHours = sub.autoUpdateIntervalHours,
                            descriptionHidden = sub.descriptionHidden,
                        ).withSubscriptionMetadata(sub.metadata)
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
                        )
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
        successMessage: String,
    ) {
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            runCatching {
                setUrl(url)
                geoDataManager.refresh(asset)
            }.onSuccess {
                _assetUpdateEvents.emit(successMessage)
                reloadActiveConnectionIfConnected()
            }.onFailure { error ->
                _assetUpdateEvents.emit(error.message ?: "Update failed")
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
}
