package com.material.xray.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.core.network.LatencyProbeResult
import com.material.xray.core.network.ServerLatencyTester
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionRefreshCoordinator
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.model.endpointSummary
import com.material.xray.service.ConnectionStateHolder
import com.material.xray.service.ConnectionEvent
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.SubscriptionUpdateScheduler
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class ServerListItem(
    val entity: ServerEntity,
    val endpointSummary: String,
    val latency: ServerLatencyState?,
)

data class ServerLatencyState(
    val latencyMs: Int,
    val usedTcpFallback: Boolean = false,
)

const val LATENCY_TESTING = Int.MIN_VALUE

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator,
    private val subscriptionUpdateScheduler: SubscriptionUpdateScheduler,
    private val connectionStateHolder: ConnectionStateHolder,
    private val routingChangeManager: RoutingChangeManager,
    private val serverLatencyTester: ServerLatencyTester,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val endpointSummaryCache = mutableMapOf<String, String>()
    private val activeConfigFile = context.filesDir.resolve("config.json")
    private val stateFile = StateFile(context)
    private val latencySemaphore = Semaphore(MAX_PARALLEL_LATENCY_TESTS)

    val connectionState: StateFlow<ConnectionState> = connectionStateHolder.state
    val connectionEvents: SharedFlow<ConnectionEvent> = connectionStateHolder.events

    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allServers: StateFlow<List<ServerEntity>> = serverRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val latencyByServerId = MutableStateFlow<Map<Long, ServerLatencyState>>(emptyMap())

    val serverItems: StateFlow<List<ServerListItem>> = combine(allServers, latencyByServerId) { servers, latencies ->
        servers.map { it.toListItem(latencies[it.id]) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serversBySubscription: StateFlow<Map<Long, List<ServerListItem>>> = serverItems
        .map { items -> items.groupBy { it.entity.subscriptionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val selectedServerId: StateFlow<Long> = settingsRepo.lastServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    val useRootService: StateFlow<Boolean> = settingsRepo.useRootService
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedServer: StateFlow<ServerConfig?> = combine(selectedServerId, allServers) { id, list ->
        list.find { it.id == id }?.let { runCatching { serverRepo.parseConfig(it) }.getOrNull() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val refreshOperations = MutableStateFlow(0)
    val isRefreshing: StateFlow<Boolean> = refreshOperations
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _runningConfig = MutableStateFlow<String?>(null)
    val runningConfig: StateFlow<String?> = _runningConfig.asStateFlow()

    init {
        refreshTunnelInterfaceState()
    }

    fun connect() {
        val server = selectedServer.value ?: return
        routingChangeManager.clearPendingChanges()
        XrayService.connect(context, server)
    }

    fun disconnect() {
        XrayService.disconnect(context)
    }

    fun refreshTunnelInterfaceState() {
        viewModelScope.launch {
            val detectedState = detectTunnelInterfaceState()
            val currentState = connectionStateHolder.state.value
            when {
                detectedState is ConnectionState.InterfaceBusy -> {
                    connectionStateHolder.update(detectedState)
                }
                detectedState is ConnectionState.Connected && currentState is ConnectionState.Disconnected -> {
                    connectionStateHolder.update(detectedState)
                    if (detectedState.corePid > 0) {
                        XrayService.restoreStatus(context)
                    }
                }
                detectedState == null && (currentState is ConnectionState.InterfaceBusy ||
                    currentState is ConnectionState.RestartRequired ||
                    (currentState is ConnectionState.Connected && currentState.corePid <= 0)
                ) -> {
                    connectionStateHolder.update(ConnectionState.Disconnected)
                }
            }
        }
    }

    private suspend fun detectTunnelInterfaceState(): ConnectionState? = withContext(Dispatchers.IO) {
        if (!settingsRepo.useRootService.first()) {
            return@withContext null
        }

        val persistedState = stateFile.read()
        val activeTunName = settingsRepo.tunName.first().trim().ifBlank { DEFAULT_TUN_NAME }

        if (!TunInterfaceDetector.isInterfaceUp(activeTunName)) {
            return@withContext null
        }

        if (activeTunName == AMBIGUOUS_TUN_NAME && TunInterfaceDetector.isVpnServiceActive(context)) {
            return@withContext ConnectionState.InterfaceBusy(activeTunName)
        }

        val persistedServerName = persistedState
            ?.serverName
            ?.takeIf { it.isNotBlank() }
        val selectedServerName = settingsRepo.lastServerId.first()
            .takeIf { it > 0 }
            ?.let { serverRepo.getById(it) }
            ?.let { entity -> runCatching { serverRepo.parseConfig(entity).name }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        ConnectionState.Connected(
            serverName = persistedServerName ?: selectedServerName ?: "Selected server",
            corePid = persistedState?.xrayPid ?: -1,
            tunName = activeTunName,
            physicalInterface = persistedState?.physicalInterface ?: "unknown",
            physicalGateway = persistedState?.physicalGateway,
            physicalTable = persistedState?.physicalTable,
            startTime = persistedState?.timestamp ?: System.currentTimeMillis(),
        )
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
                if (state is ConnectionState.Connected) {
                    XrayService.switchServer(context, config)
                } else {
                    XrayService.connect(context, config)
                }
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
                runCatching { subscriptionRefreshCoordinator.updateSubscription(sub, name, url) }
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            withRefreshTracking {
                runCatching { subscriptionRefreshCoordinator.refreshAll() }
            }
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            withRefreshTracking {
                runCatching { subscriptionRefreshCoordinator.refreshSubscription(sub.id, sub.url) }
            }
        }
    }

    fun setSubscriptionAutoUpdateInterval(subId: Long, intervalHours: Int) {
        viewModelScope.launch {
            subscriptionRepo.setAutoUpdateInterval(subId, intervalHours)
            subscriptionUpdateScheduler.enqueueDueCheckNow()
        }
    }

    fun setSubscriptionDescriptionHidden(subId: Long, hidden: Boolean) {
        viewModelScope.launch {
            subscriptionRepo.setDescriptionHidden(subId, hidden)
        }
    }

    fun testLatency(server: ServerEntity) {
        viewModelScope.launch {
            latencyByServerId.update { it + (server.id to ServerLatencyState(LATENCY_TESTING)) }
            val latency = measureLatency(server)
            latencyByServerId.update { it + (server.id to latency) }
        }
    }

    fun testSubscriptionLatencies(sub: SubscriptionEntity) {
        viewModelScope.launch {
            allServers.value
                .filter { it.subscriptionId == sub.id }
                .forEach { server ->
                    launch {
                        latencyByServerId.update { it + (server.id to ServerLatencyState(LATENCY_TESTING)) }
                        val latency = latencySemaphore.withPermit { measureLatency(server) }
                        latencyByServerId.update { it + (server.id to latency) }
                    }
                }
        }
    }

    fun testAllLatencies() {
        viewModelScope.launch {
            allServers.value.forEach { server ->
                launch {
                    latencyByServerId.update { it + (server.id to ServerLatencyState(LATENCY_TESTING)) }
                    val latency = latencySemaphore.withPermit { measureLatency(server) }
                    latencyByServerId.update { it + (server.id to latency) }
                }
            }
        }
    }

    private fun ServerEntity.toListItem(latency: ServerLatencyState?): ServerListItem {
        val summary = endpointSummaryCache.getOrPut(configJson) {
            runCatching {
                val config = json.decodeFromString<ServerConfig>(configJson)
                config.endpointSummary()
            }.getOrElse {
                "${protocol.lowercase()} • unknown • unknown"
            }
        }
        return ServerListItem(entity = this, endpointSummary = summary, latency = latency)
    }

    private suspend fun measureLatency(server: ServerEntity): ServerLatencyState {
        val config = runCatching { serverRepo.parseConfig(server) }.getOrNull()
            ?: return ServerLatencyState(latencyMs = -1)
        return serverLatencyTester.measure(
            server = config,
            probeUrl = settingsRepo.latencyCheckUrl.first(),
            dnsServers = settingsRepo.latencyDnsServers.first(),
        ).toUiState()
    }

    private fun LatencyProbeResult.toUiState(): ServerLatencyState =
        ServerLatencyState(
            latencyMs = latencyMs,
            usedTcpFallback = usedTcpFallback,
        )

    private suspend fun withRefreshTracking(block: suspend () -> Unit) {
        refreshOperations.update { it + 1 }
        try {
            block()
        } finally {
            refreshOperations.update { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    private companion object {
        const val DEFAULT_TUN_NAME = "xray0"
        const val AMBIGUOUS_TUN_NAME = "tun0"
        const val MAX_PARALLEL_LATENCY_TESTS = 1
    }
}
