package com.materialxray.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.AppBypassEntity
import com.materialxray.data.db.entity.SubscriptionEntity
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.model.BackupData
import com.materialxray.model.ConnectionState
import com.materialxray.model.XrayLogLevel
import com.materialxray.model.toSubscriptionMetadata
import com.materialxray.model.withSubscriptionMetadata
import com.materialxray.service.ConnectionStateHolder
import com.materialxray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val appBypassDao: AppBypassDao,
    private val connectionStateHolder: ConnectionStateHolder,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val tunName = settingsRepo.tunName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "xray0")
    val dnsServers =
        settingsRepo.dnsServers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1.1.1.1,8.8.8.8")
    val autoConnect = settingsRepo.autoConnect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val xrayLogLevel = settingsRepo.xrayLogLevel.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        XrayLogLevel.default,
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

    fun setTunName(name: String) = viewModelScope.launch { settingsRepo.setTunName(name) }
    fun setDnsServers(servers: String) = viewModelScope.launch { settingsRepo.setDnsServers(servers) }
    fun setAutoConnect(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(enabled) }
    fun setXrayLogLevel(level: XrayLogLevel) = viewModelScope.launch {
        if (level == xrayLogLevel.value) return@launch
        settingsRepo.setXrayLogLevel(level)
        if (connectionStateHolder.state.value is ConnectionState.Connected) {
            XrayService.reload(context)
        }
    }

    fun setGeoipUrl(url: String) = viewModelScope.launch { settingsRepo.setGeoipUrl(url) }
    fun setGeositeUrl(url: String) = viewModelScope.launch { settingsRepo.setGeositeUrl(url) }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val subs = subscriptionDao.getAll()
                val bypassed = appBypassDao.getExcluded().map { it.packageName }
                val settings = settingsRepo.getAllAsMap()

                val backup = BackupData(
                    subscriptions = subs.map { sub ->
                        BackupData.BackupSubscription(
                            name = sub.name,
                            url = sub.url,
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
                        ).withSubscriptionMetadata(sub.metadata)
                    )
                }
                backup.bypassedApps.forEach { pkg ->
                    appBypassDao.upsert(AppBypassEntity(packageName = pkg, uid = 0, excluded = true))
                }
                settingsRepo.restoreFromMap(backup.settings)
            }
        }
    }
}
