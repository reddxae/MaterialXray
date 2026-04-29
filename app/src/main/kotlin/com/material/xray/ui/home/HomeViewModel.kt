package com.material.xray.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.service.ConnectionStateHolder
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.XrayService
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
    val latencyMs: Int?,
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
    private val activeConfigFile = context.filesDir.resolve("config.json")

    val connectionState: StateFlow<ConnectionState> = connectionStateHolder.state

    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allServers: StateFlow<List<ServerEntity>> = serverRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val latencyByServerId = MutableStateFlow<Map<Long, Int>>(emptyMap())

    val serverItems: StateFlow<List<ServerListItem>> = combine(allServers, latencyByServerId) { servers, latencies ->
        servers.map { it.toListItem(latencies[it.id]) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serversBySubscription: StateFlow<Map<Long, List<ServerListItem>>> = serverItems
        .map { items -> items.groupBy { it.entity.subscriptionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val selectedServerId: StateFlow<Long> = settingsRepo.lastServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    val selectedServer: StateFlow<ServerConfig?> = combine(selectedServerId, allServers) { id, list ->
        list.find { it.id == id }?.let { runCatching { serverRepo.parseConfig(it) }.getOrNull() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val refreshOperations = MutableStateFlow(0)
    val isRefreshing: StateFlow<Boolean> = refreshOperations
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _runningConfig = MutableStateFlow<String?>(null)
    val runningConfig: StateFlow<String?> = _runningConfig.asStateFlow()

    fun connect() {
        val server = selectedServer.value ?: return
        routingChangeManager.clearPendingChanges()
        XrayService.connect(context, server)
    }

    fun disconnect() {
        XrayService.disconnect(context)
    }

    fun showRunningConfig() {
        viewModelScope.launch {
            _runningConfig.value = withContext(Dispatchers.IO) {
                runCatching { activeConfigFile.takeIf { it.isFile }?.readText() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "No active Xray config file was found."
            }
        }
    }

    fun dismissRunningConfig() {
        _runningConfig.value = null
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

    fun updateSubscription(sub: SubscriptionEntity, name: String, url: String) {
        viewModelScope.launch {
            withRefreshTracking {
                runCatching { subscriptionRepo.update(sub, name, url) }
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            withRefreshTracking {
                runCatching { subscriptionRepo.refreshAll() }
            }
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            withRefreshTracking {
                runCatching { subscriptionRepo.refresh(sub.id, sub.url) }
            }
        }
    }

    fun testLatency(server: ServerEntity) {
        viewModelScope.launch {
            val latency = withContext(Dispatchers.IO) { measureLatency(server.address, server.port) }
            latencyByServerId.update { it + (server.id to latency) }
        }
    }

    fun testSubscriptionLatencies(sub: SubscriptionEntity) {
        viewModelScope.launch {
            allServers.value
                .filter { it.subscriptionId == sub.id }
                .forEach { server ->
                    launch {
                        val latency = withContext(Dispatchers.IO) { measureLatency(server.address, server.port) }
                        latencyByServerId.update { it + (server.id to latency) }
                    }
                }
        }
    }

    fun testAllLatencies() {
        viewModelScope.launch {
            allServers.value.forEach { server ->
                launch {
                    val latency = withContext(Dispatchers.IO) { measureLatency(server.address, server.port) }
                    latencyByServerId.update { it + (server.id to latency) }
                }
            }
        }
    }

    private fun ServerEntity.toListItem(latencyMs: Int?): ServerListItem {
        val summary = endpointSummaryCache.getOrPut(configJson) {
            runCatching {
                val config = json.decodeFromString<ServerConfig>(configJson)
                "${config.protocol.displayName.uppercase()} | ${config.transport.type.uppercase()} | ${config.security.type.uppercase()}"
            }.getOrElse {
                "$protocol | UNKNOWN | UNKNOWN"
            }
        }
        return ServerListItem(entity = this, endpointSummary = summary, latencyMs = latencyMs)
    }

    private fun measureLatency(address: String, port: Int): Int = runCatching {
        val start = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress(address, port), 3000) }
        (System.currentTimeMillis() - start).toInt()
    }.getOrElse { -1 }

    private suspend fun withRefreshTracking(block: suspend () -> Unit) {
        refreshOperations.update { it + 1 }
        try {
            block()
        } finally {
            refreshOperations.update { current -> (current - 1).coerceAtLeast(0) }
        }
    }
}
