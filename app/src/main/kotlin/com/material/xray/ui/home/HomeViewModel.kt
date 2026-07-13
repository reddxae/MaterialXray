package com.material.xray.ui.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.locale.appLocaleChanges
import com.material.xray.core.locale.forAppLanguage
import com.material.xray.core.locale.localizedString
import com.material.xray.core.network.LatencyProbeResult
import com.material.xray.core.network.ServerLatencyTester
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.AppUpdateRepository
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRefreshCoordinator
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.data.repository.toSubscriptionAppRouting
import com.material.xray.data.repository.toSubscriptionRouting
import com.material.xray.model.AppUpdate
import com.material.xray.model.ConnectionState
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.endpointSummary
import com.material.xray.model.maskedBalancerOutboundAddress
import com.material.xray.model.matchesBalancerOutbound
import com.material.xray.model.proxyOutboundCount
import com.material.xray.service.AlwaysOnVpnState
import com.material.xray.service.AppUpdateChecker
import com.material.xray.service.AppUpdateInstallProgress
import com.material.xray.service.AppUpdateInstaller
import com.material.xray.service.ConnectionEvent
import com.material.xray.service.ConnectionStateHolder
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.SubscriptionUpdateScheduler
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class ServerListItem(
    val entity: ServerEntity,
    val endpointSummary: String,
    val latency: ServerLatencyState?,
)

data class ServerLatencyState(
    val latencyMs: Int,
    val method: PingMethod? = null,
)

data class ActiveBalancerServerState(
    val title: String,
    val latencyMs: Long?,
)

data class SubscriptionRoutingData(
    val appRouting: SubscriptionAppRouting?,
    val routing: SubscriptionRouting?,
)

sealed interface HomeUiEvent {
    data class Toast(@param:StringRes val messageResId: Int) : HomeUiEvent
}

const val LATENCY_TESTING = Int.MIN_VALUE

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdateChecker: AppUpdateChecker,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val serverRepo: ServerRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val subscriptionRoutingRepository: SubscriptionRoutingRepository,
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator,
    private val subscriptionUpdateScheduler: SubscriptionUpdateScheduler,
    private val connectionStateHolder: ConnectionStateHolder,
    alwaysOnVpnState: AlwaysOnVpnState,
    private val routingChangeManager: RoutingChangeManager,
    private val serverLatencyTester: ServerLatencyTester,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val endpointSummaryCache = mutableMapOf<String, String>()
    private val activeConfigFile = context.filesDir.resolve("config.json")
    private val stateFile = StateFile(context)
    private var latencyJob: Job? = null
    private var latencyRunId = 0L
    private var activeLatencyServerIds = emptySet<Long>()
    private val latencySemaphore = Semaphore(MAX_CONCURRENT_LATENCY_TESTS)

    val connectionState: StateFlow<ConnectionState> = connectionStateHolder.state
    val alwaysOnVpn: StateFlow<Boolean> = alwaysOnVpnState.active
    val connectionEvents: SharedFlow<ConnectionEvent> = connectionStateHolder.events
    private val _uiEvents = MutableSharedFlow<HomeUiEvent>()
    val uiEvents: SharedFlow<HomeUiEvent> = _uiEvents.asSharedFlow()

    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableUpdate: StateFlow<AppUpdate?> = appUpdateRepository.availableUpdate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val appUpdateInstallProgress: StateFlow<AppUpdateInstallProgress?> = appUpdateInstaller.installProgress

    private val allServers: StateFlow<List<ServerEntity>> = serverRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val latencyByServerId = MutableStateFlow<Map<Long, ServerLatencyState>>(emptyMap())

    val serverItems: StateFlow<List<ServerListItem>> = combine(
        allServers,
        latencyByServerId,
        appLocaleChanges.onStart { emit(Unit) },
    ) { servers, latencies, _ -> servers.map { it.toListItem(latencies[it.id]) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serversBySubscription: StateFlow<Map<Long, List<ServerListItem>>> = serverItems
        .map { items -> items.groupBy { it.entity.subscriptionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val selectedServerId: StateFlow<Long> = settingsRepo.lastServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    val useRootService: StateFlow<Boolean> = settingsRepo.useRootService
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultPingMethod: StateFlow<PingMethod> = settingsRepo.defaultPingMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PingMethod.default)

    val routingPolicyControl: StateFlow<RoutingPolicyControl> = settingsRepo.routingPolicyControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.default)

    val selectedServer: StateFlow<ServerConfig?> = combine(selectedServerId, allServers) { id, list ->
        list.find { it.id == id }?.let { runCatching { serverRepo.parseConfig(it) }.getOrNull() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeBalancerServer: StateFlow<ActiveBalancerServerState?> = combine(
        connectionStateHolder.activeBalancerSelection,
        selectedServerId,
        selectedServer,
        allServers,
    ) { selection, selectedId, selectedConfig, servers ->
        if (selection == null || selectedConfig == null) return@combine null
        val selectedEntity = servers.firstOrNull { it.id == selectedId } ?: return@combine null
        val matchingTitle = servers.asSequence()
            .filter { it.id != selectedId && it.subscriptionId == selectedEntity.subscriptionId }
            .firstOrNull { entity ->
                runCatching {
                    selectedConfig.matchesBalancerOutbound(selection.outboundTag, serverRepo.parseConfig(entity))
                }.getOrDefault(false)
            }
            ?.name
        val title = matchingTitle
            ?: selectedConfig.maskedBalancerOutboundAddress(selection.outboundTag)
            ?: return@combine null
        ActiveBalancerServerState(title = title, latencyMs = selection.latencyMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val refreshOperations = MutableStateFlow(0)
    val isRefreshing: StateFlow<Boolean> = refreshOperations
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _runningConfig = MutableStateFlow<String?>(null)
    val runningConfig: StateFlow<String?> = _runningConfig.asStateFlow()
    private val _pendingSubscriptionRouting = MutableStateFlow<SubscriptionRoutingData?>(null)
    val pendingSubscriptionRouting: StateFlow<SubscriptionRoutingData?> = _pendingSubscriptionRouting.asStateFlow()
    val showInstallPermissionRationale: StateFlow<Boolean> = appUpdateInstaller.installPermissionRationaleRequired

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
                detectedState == null &&
                    (
                        currentState is ConnectionState.InterfaceBusy ||
                            currentState is ConnectionState.RestartRequired ||
                            (currentState is ConnectionState.Connected && currentState.corePid <= 0)
                        ) -> {
                    connectionStateHolder.update(ConnectionState.Disconnected)
                }
            }
        }
    }

    fun checkForAppUpdateIfDue() {
        viewModelScope.launch {
            try {
                appUpdateChecker.check()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Update checks are best-effort and should not interrupt the Home screen.
            }
        }
    }

    fun installAppUpdate(update: AppUpdate) {
        viewModelScope.launch {
            try {
                appUpdateInstaller.install(update)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiEvents.emit(HomeUiEvent.Toast(R.string.home_app_update_install_failed))
            }
        }
    }

    fun resumePendingAppUpdateInstall() {
        viewModelScope.launch {
            try {
                appUpdateInstaller.resumePendingInstall()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiEvents.emit(HomeUiEvent.Toast(R.string.home_app_update_install_failed))
            }
        }
    }

    fun confirmInstallPermissionRationale() {
        viewModelScope.launch {
            try {
                appUpdateInstaller.confirmInstallPermissionRationale()
            } catch (_: Exception) {
                _uiEvents.emit(HomeUiEvent.Toast(R.string.home_app_update_install_failed))
            }
        }
    }

    fun dismissInstallPermissionRationale() {
        appUpdateInstaller.dismissInstallPermissionRationale()
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
            serverName = persistedServerName ?: selectedServerName ?: context.localizedString(R.string.home_selected_server),
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
                    ?: context.localizedString(R.string.home_no_active_xray_config)
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
            val serverEntity = allServers.value.find { it.id == serverId }
            applyProviderRoutingForServer(serverEntity)

            val state = connectionState.value
            if (state is ConnectionState.Connected || state is ConnectionState.Error) {
                serverEntity ?: return@launch
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

    fun addSubscription(
        name: String,
        url: String,
        preferJson: Boolean,
        userAgentMode: SubscriptionUserAgentMode,
        customUserAgent: String,
        customHeaders: String,
    ) {
        viewModelScope.launch {
            runCatching { subscriptionRepo.add(name, url, preferJson, userAgentMode, customUserAgent, customHeaders) }
                .onFailure { _uiEvents.emit(HomeUiEvent.Toast(R.string.home_unable_to_fetch_link)) }
        }
    }

    fun addLink(link: String) {
        viewModelScope.launch {
            runCatching { subscriptionRepo.addLink(link) }
                .onFailure { _uiEvents.emit(HomeUiEvent.Toast(R.string.home_unable_to_fetch_link)) }
        }
    }

    fun requestApplySubscriptionRouting(sub: SubscriptionEntity) {
        val routing = SubscriptionRoutingData(
            appRouting = sub.toSubscriptionAppRouting(),
            routing = sub.toSubscriptionRouting(),
        )
        if (routing.appRouting == null && routing.routing == null) return
        _pendingSubscriptionRouting.value = routing
    }

    fun applyPendingSubscriptionRouting() {
        viewModelScope.launch {
            val data = _pendingSubscriptionRouting.value ?: return@launch
            data.appRouting?.let { applySubscriptionRouting(it) }
            data.routing?.let { applySubscriptionRouting(it) }
            _pendingSubscriptionRouting.value = null
        }
    }

    fun dismissPendingSubscriptionRouting() {
        _pendingSubscriptionRouting.value = null
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch { subscriptionRepo.delete(sub) }
    }

    fun updateSubscription(
        sub: SubscriptionEntity,
        name: String,
        url: String,
        preferJson: Boolean,
        autoUpdateIntervalHours: Int,
        userAgentMode: SubscriptionUserAgentMode,
        customUserAgent: String,
        customHeaders: String,
    ) {
        viewModelScope.launch {
            val normalizedIntervalHours = autoUpdateIntervalHours.coerceAtLeast(0)
            val normalizedCustomUserAgent = customUserAgent.trim().ifBlank { null }
            val normalizedCustomHeaders = customHeaders.trim().ifBlank { null }
            val identityChanged = userAgentMode != SubscriptionUserAgentMode.fromValue(sub.userAgentMode) ||
                normalizedCustomUserAgent != sub.customUserAgent ||
                normalizedCustomHeaders != sub.customHeaders
            val hasSubscriptionChanges = name.trim() != sub.name ||
                url.trim() != sub.url ||
                preferJson != (sub.preferJson ?: true) ||
                identityChanged
            val hasIntervalChanges = normalizedIntervalHours != sub.autoUpdateIntervalHours

            if (hasSubscriptionChanges) {
                withRefreshTracking {
                    runCatching {
                        subscriptionRefreshCoordinator.updateSubscription(
                            sub.copy(
                                preferJson = preferJson,
                                autoUpdateIntervalHours = normalizedIntervalHours,
                                userAgentMode = userAgentMode.value,
                                customUserAgent = normalizedCustomUserAgent,
                                customHeaders = normalizedCustomHeaders,
                            ),
                            name,
                            url,
                        )
                    }
                }
            } else if (hasIntervalChanges) {
                subscriptionRepo.setAutoUpdateInterval(sub.id, normalizedIntervalHours)
            }

            if (hasIntervalChanges) {
                subscriptionUpdateScheduler.enqueueDueCheckNow()
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

    private suspend fun applySubscriptionRouting(routing: SubscriptionAppRouting) {
        if (subscriptionAppRoutingRepository.apply(routing)) {
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    private suspend fun applySubscriptionRouting(routing: SubscriptionRouting) {
        if (subscriptionRoutingRepository.apply(routing)) {
            routingChangeManager.markPendingChanges(PendingRoutingChange.XRAY_CONFIG)
        }
    }

    private suspend fun applyProviderRoutingForServer(server: ServerEntity?) {
        if (server == null || routingPolicyControl.value != RoutingPolicyControl.SubscriptionProvider) return
        val appRoutingChanged = subscriptionAppRoutingRepository.applyForSubscription(server.subscriptionId)
        val routingChanged = subscriptionRoutingRepository.applyForSubscription(server.subscriptionId)
        if (appRoutingChanged || routingChanged) {
            if (connectionState.value is ConnectionState.Connected) {
                routingChangeManager.markPendingChanges(
                    if (routingChanged) PendingRoutingChange.XRAY_CONFIG else PendingRoutingChange.APP_ROUTING,
                )
            }
        }
    }

    fun setDefaultPingMethod(method: PingMethod) {
        viewModelScope.launch {
            settingsRepo.setDefaultPingMethod(method)
        }
    }

    fun testLatency(server: ServerEntity) {
        restartLatencyTests(listOf(server))
    }

    fun testSubscriptionLatencies(sub: SubscriptionEntity) {
        restartLatencyTests(
            servers = allServers.value.filter { it.subscriptionId == sub.id },
            sortDuringTest = true,
        )
    }

    fun testAllLatencies() {
        restartLatencyTests(allServers.value)
    }

    private fun restartLatencyTests(servers: List<ServerEntity>, sortDuringTest: Boolean = false) {
        latencyJob?.cancel()

        val runId = ++latencyRunId
        val pingMethod = defaultPingMethod.value
        val targetServers = servers.distinctBy { it.id }
        val targetServerIds = targetServers.map { it.id }.toSet()
        val canceledOnlyServerIds = activeLatencyServerIds - targetServerIds
        activeLatencyServerIds = targetServerIds

        latencyByServerId.update { current ->
            (current - canceledOnlyServerIds) + targetServers.associate { server ->
                server.id to ServerLatencyState(LATENCY_TESTING, method = pingMethod)
            }
        }

        if (targetServers.isEmpty()) {
            latencyJob = null
            return
        }

        latencyJob = viewModelScope.launch {
            runLatencyProbes(
                runId = runId,
                servers = targetServers,
                method = pingMethod,
                sortDuringTest = sortDuringTest && settingsRepo.sortOutboundsByLatency.first(),
            )
        }
    }

    private suspend fun runLatencyProbes(
        runId: Long,
        servers: List<ServerEntity>,
        method: PingMethod,
        sortDuringTest: Boolean,
    ) = supervisorScope {
        val probeJobs = servers.map { server ->
            launch { runLatencyProbe(runId, server, method) }
        }
        val sortingJob = if (sortDuringTest) {
            launch {
                while (isActive) {
                    delay(LATENCY_SORT_INTERVAL_MILLIS)
                    updateServerSortOrder(runId, servers)
                }
            }
        } else {
            null
        }

        probeJobs.joinAll()
        sortingJob?.cancelAndJoin()
        if (sortDuringTest) updateServerSortOrder(runId, servers)
    }

    private suspend fun updateServerSortOrder(runId: Long, servers: List<ServerEntity>) {
        if (latencyRunId != runId) return
        serverRepo.updateSortOrders(
            sortedServerIdsByLatency(servers.map { it.id }, latencyByServerId.value),
        )
    }

    private suspend fun runLatencyProbe(runId: Long, server: ServerEntity, method: PingMethod) {
        try {
            val latency = try {
                latencySemaphore.withPermit { measureLatency(server, method) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ServerLatencyState(latencyMs = -1, method = method)
            }

            if (latencyRunId == runId) {
                latencyByServerId.update { it + (server.id to latency) }
            }
        } finally {
            if (latencyRunId == runId) {
                activeLatencyServerIds = activeLatencyServerIds - server.id
            }
        }
    }

    private fun ServerEntity.toListItem(latency: ServerLatencyState?): ServerListItem {
        val resources = context.forAppLanguage().resources
        val localeKey = resources.configuration.locales.toLanguageTags()
        val summary = endpointSummaryCache.getOrPut("$localeKey\u0000$configJson") {
            runCatching {
                val config = json.decodeFromString<ServerConfig>(configJson)
                val outboundCount = config.proxyOutboundCount()
                if (outboundCount == null) {
                    config.endpointSummary()
                } else {
                    resources.getQuantityString(
                        R.plurals.home_server_multiconnect_summary,
                        outboundCount,
                        outboundCount,
                    )
                }
            }.getOrElse {
                val unknown = context.localizedString(R.string.home_server_endpoint_unknown)
                "${protocol.lowercase(Locale.ROOT)} • $unknown • $unknown"
            }
        }
        return ServerListItem(entity = this, endpointSummary = summary, latency = latency)
    }

    private suspend fun measureLatency(server: ServerEntity, method: PingMethod): ServerLatencyState {
        val config = runCatching { serverRepo.parseConfig(server) }.getOrNull()
            ?: return ServerLatencyState(latencyMs = -1, method = method)
        return serverLatencyTester.measure(
            server = config,
            method = method,
            probeUrl = settingsRepo.latencyCheckUrl.first(),
            dnsServers = settingsRepo.latencyDnsServers.first(),
            allowIpv6 = settingsRepo.allowIpv6.first(),
        ).toUiState()
    }

    private fun LatencyProbeResult.toUiState(): ServerLatencyState = ServerLatencyState(
        latencyMs = latencyMs,
        method = method,
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
        const val MAX_CONCURRENT_LATENCY_TESTS = 10
        const val LATENCY_SORT_INTERVAL_MILLIS = 500L
    }
}

internal fun sortedServerIdsByLatency(
    serverIds: List<Long>,
    latencyByServerId: Map<Long, ServerLatencyState>,
): List<Long> = serverIds.sortedWith(
    compareBy<Long>(
        { serverId -> latencyByServerId[serverId]?.latencyMs?.let { it < 0 } ?: true },
        { serverId -> latencyByServerId[serverId]?.latencyMs?.takeIf { it >= 0 } ?: 0 },
    ),
)
