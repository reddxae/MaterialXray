package com.materialxray.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity
import com.materialxray.data.repository.ServerRepository
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.data.repository.SubscriptionRepository
import com.materialxray.model.ConnectionState
import com.materialxray.model.ServerConfig
import com.materialxray.service.ConnectionStateHolder
import com.materialxray.service.RoutingChangeManager
import com.materialxray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class ServerListItem(
    val entity: ServerEntity,
    val endpointSummary: String,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val connectionStateHolder: ConnectionStateHolder,
    private val routingChangeManager: RoutingChangeManager,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val endpointSummaryCache = mutableMapOf<String, String>()

    val connectionState: StateFlow<ConnectionState> = connectionStateHolder.state

    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allServers: StateFlow<List<ServerEntity>> = serverRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serverItems: StateFlow<List<ServerListItem>> = allServers
        .map { servers -> servers.map { it.toListItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serversBySubscription: StateFlow<Map<Long, List<ServerListItem>>> = serverItems
        .map { items -> items.groupBy { it.entity.subscriptionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val selectedServerId: StateFlow<Long> = settingsRepo.lastServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    val selectedServer: StateFlow<ServerConfig?> = combine(selectedServerId, allServers) { id, list ->
        list.find { it.id == id }?.let { runCatching { serverRepo.parseConfig(it) }.getOrNull() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    fun connect() {
        val server = selectedServer.value ?: return
        routingChangeManager.clearPendingChanges()
        XrayService.connect(context, server)
    }

    fun disconnect() {
        XrayService.disconnect(context)
    }

    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            if (serverId == selectedServerId.value) return@launch
            settingsRepo.setLastServerId(serverId)

            val state = connectionState.value
            if (state is ConnectionState.Connected || state is ConnectionState.Error) {
                val serverEntity = allServers.value.find { it.id == serverId } ?: return@launch
                val config = runCatching { serverRepo.parseConfig(serverEntity) }.getOrNull() ?: return@launch
                routingChangeManager.clearPendingChanges()
                XrayService.connect(context, config)
            }
        }
    }

    fun addSubscription(name: String, url: String) {
        viewModelScope.launch { runCatching { subscriptionRepo.add(name, url) } }
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch { subscriptionRepo.delete(sub) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { subscriptionRepo.refreshAll() }
            _isRefreshing.value = false
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { subscriptionRepo.refresh(sub.id, sub.url) }
            _isRefreshing.value = false
        }
    }

    fun testLatency(server: ServerEntity) {
        viewModelScope.launch {
            val latency = withContext(Dispatchers.IO) { measureLatency(server.address, server.port) }
            serverRepo.updateLatency(server.id, latency)
        }
    }

    fun testSubscriptionLatencies(sub: SubscriptionEntity) {
        viewModelScope.launch {
            allServers.value
                .filter { it.subscriptionId == sub.id }
                .forEach { server ->
                    launch {
                        val latency = withContext(Dispatchers.IO) { measureLatency(server.address, server.port) }
                        serverRepo.updateLatency(server.id, latency)
                    }
                }
        }
    }

    fun testAllLatencies() {
        viewModelScope.launch {
            allServers.value.forEach { server ->
                launch {
                    val latency = withContext(Dispatchers.IO) { measureLatency(server.address, server.port) }
                    serverRepo.updateLatency(server.id, latency)
                }
            }
        }
    }

    private fun ServerEntity.toListItem(): ServerListItem {
        val summary = endpointSummaryCache.getOrPut(configJson) {
            runCatching {
                val config = json.decodeFromString<ServerConfig>(configJson)
                "${config.protocol.displayName.uppercase()} | ${config.transport.type.uppercase()} | ${config.security.type.uppercase()}"
            }.getOrElse {
                "$protocol | UNKNOWN | UNKNOWN"
            }
        }
        return ServerListItem(entity = this, endpointSummary = summary)
    }

    private fun measureLatency(address: String, port: Int): Int = runCatching {
        val start = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress(address, port), 3000) }
        (System.currentTimeMillis() - start).toInt()
    }.getOrElse { -1 }
}
