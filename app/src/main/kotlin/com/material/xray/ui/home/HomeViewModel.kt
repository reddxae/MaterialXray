package com.material.xray.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.core.network.ServerLatencyTester
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.parser.SubscriptionFetchException
import com.material.xray.data.repository.AppUpdateRepository
import com.material.xray.data.repository.ProviderRoutingActiveUpdate
import com.material.xray.data.repository.ProviderRoutingAvailability
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.ServerSelectionCoordinator
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRefreshCoordinator
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.data.repository.selectedProviderRoutingAvailability
import com.material.xray.data.repository.toSubscriptionAppRouting
import com.material.xray.data.repository.toSubscriptionRouting
import com.material.xray.model.AppUpdate
import com.material.xray.model.ConnectionState
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.ServerConfig
import com.material.xray.model.SessionTrafficMetrics
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.maskedBalancerOutboundAddress
import com.material.xray.model.matchesBalancerOutbound
import com.material.xray.service.AlwaysOnVpnState
import com.material.xray.service.AppUpdateChecker
import com.material.xray.service.AppUpdateInstallProgress
import com.material.xray.service.AppUpdateInstaller
import com.material.xray.service.ConnectionEvent
import com.material.xray.service.ConnectionRuntimeManager
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.SubscriptionUpdateScheduler
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ServerListItem(
    val entity: ServerEntity,
    val endpointSummary: String,
    val latency: ServerLatencyState?,
)

data class ServerLatencyState(
    val latencyMs: Int,
    val method: PingMethod? = null,
    val tcpingLatencyMs: Int? = null,
    val httpingLatencyMs: Int? = null,
)

data class ActiveBalancerServerState(
    val title: String,
    val latencyMs: Long?,
)

data class SubscriptionRoutingData(
    val appRouting: SubscriptionAppRouting?,
    val routing: SubscriptionRouting?,
)

internal fun SubscriptionEntity.manualRoutingData(
    policy: RoutingPolicyControl,
    selectedProvider: ProviderRoutingAvailability?,
) = SubscriptionRoutingData(
    appRouting = toSubscriptionAppRouting().takeUnless {
        policy == RoutingPolicyControl.SubscriptionProvider && selectedProvider?.appRoutingProvided == true
    },
    routing = toSubscriptionRouting().takeUnless {
        policy == RoutingPolicyControl.SubscriptionProvider && selectedProvider?.xrayRoutingProvided == true
    },
)

sealed interface HomeUiEvent {
    data class Toast(val message: String) : HomeUiEvent
}

const val LATENCY_TESTING = Int.MIN_VALUE
private const val SERVER_SELECTION_SETTLE_MILLIS = 200L

@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    homeDataState: HomeDataState,
    private val settingsRepo: SettingsRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val appUpdateChecker: AppUpdateChecker,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val serverRepo: ServerRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val subscriptionRoutingRepository: SubscriptionRoutingRepository,
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator,
    private val serverSelectionCoordinator: ServerSelectionCoordinator,
    private val providerRoutingCoordinator: ProviderRoutingCoordinator,
    private val subscriptionUpdateScheduler: SubscriptionUpdateScheduler,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val connectionRuntimeManager: ConnectionRuntimeManager,
    private val activeConfigOverrideStore: ActiveConfigOverrideStore,
    alwaysOnVpnState: AlwaysOnVpnState,
    private val routingChangeManager: RoutingChangeManager,
    private val serverLatencyTester: ServerLatencyTester,
) : ViewModel() {
    private var serverSelectionJob: Job? = null
    private var latencyJob: Job? = null
    private var latencyRunId = 0L
    private var activeLatencyServerIds = emptySet<Long>()
    private val latencySemaphore = Semaphore(MAX_CONCURRENT_LATENCY_TESTS)

    val connectionState: StateFlow<ConnectionState> = connectionStateCoordinator.state
    internal val connectionProgress = connectionStateCoordinator.connectionProgress
    val alwaysOnVpn: StateFlow<Boolean> = alwaysOnVpnState.active
    val connectionEvents: Flow<ConnectionEvent> = connectionStateCoordinator.events
    private val _uiEvents = Channel<HomeUiEvent>(Channel.BUFFERED)
    val uiEvents: Flow<HomeUiEvent> = _uiEvents.receiveAsFlow()

    // The home data is shared process-wide and loaded eagerly on app startup, so on a typical
    // cold start every flow derived from it below starts out with the loaded snapshot as its
    // initial value instead of an empty placeholder, and the first composed frame is already
    // fully populated. `null` means the snapshot has not been built yet.
    private val homeData: StateFlow<HomeData?> = homeDataState.data

    val subscriptions: StateFlow<List<SubscriptionEntity>?> = homeData
        .map { it?.subscriptions }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.subscriptions)

    val availableUpdate: StateFlow<AppUpdate?> = appUpdateRepository.availableUpdate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val appUpdateInstallProgress: StateFlow<AppUpdateInstallProgress?> = appUpdateInstaller.installProgress

    private val allServers: StateFlow<List<ServerEntity>> = homeData
        .map { it?.servers.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.servers.orEmpty())
    private val latencyByServerId = MutableStateFlow<Map<Long, ServerLatencyState>>(emptyMap())

    val serverItems: StateFlow<List<ServerListItem>> = combine(
        homeData,
        latencyByServerId,
    ) { data, latencies ->
        data?.serverItems.orEmpty().map { item ->
            latencies[item.entity.id]?.let { item.copy(latency = it) } ?: item
        }
    }
        // Overlaying the latency states copies every list item and reruns on each probe result;
        // keep that churn off the main dispatcher.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.serverItems.orEmpty())

    val serversBySubscription: StateFlow<Map<Long, List<ServerListItem>>> = serverItems
        .map { items -> items.groupBy { it.entity.subscriptionId } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            serverItems.value.groupBy { it.entity.subscriptionId },
        )

    val selectedServerId: StateFlow<Long> = homeData
        .map { it?.selectedServerId ?: -1L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.selectedServerId ?: -1L)

    val useRootService: StateFlow<Boolean> = settingsRepo.useRootService
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showAdvancedOptions: StateFlow<Boolean> = settingsRepo.showAdvancedOptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val defaultPingMethod: StateFlow<PingMethod> = settingsRepo.defaultPingMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PingMethod.default)
    private val showBothLatencyResults: StateFlow<Boolean> = settingsRepo.showBothLatencyResults
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val routingPolicyControl: StateFlow<RoutingPolicyControl> = settingsRepo.routingPolicyControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.default)
    internal val providerRoutingAvailability: StateFlow<ProviderRoutingAvailability?> = homeData
        .map { data ->
            data?.let {
                selectedProviderRoutingAvailability(it.selectedServerId, it.servers, it.subscriptions)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            homeData.value?.let {
                selectedProviderRoutingAvailability(it.selectedServerId, it.servers, it.subscriptions)
            },
        )

    val selectedServer: StateFlow<ServerConfig?> = homeData
        .map { it?.selectedServer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), homeData.value?.selectedServer)

    val activeBalancerServer: StateFlow<ActiveBalancerServerState?> = combine(
        connectionStateCoordinator.activeBalancerSelection,
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
    }
        // Matching the balancer outbound parses server configs from the whole subscription.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * Live traffic counters for the connection banner. Subscribing here is what makes the service
     * poll Xray at all, so the grace period keeps a tab switch from restarting its loop.
     */
    val sessionTraffic: StateFlow<SessionTrafficMetrics?> = connectionStateCoordinator.sessionTraffic
        .stateIn(viewModelScope, liveReadingSharing(), null)

    /**
     * Round-trip time to whatever the tunnel is currently using, measured by the service against
     * the config it actually connected with. Subscribing here is what starts the probe, so nothing
     * is measured for the banner while it is off screen.
     */
    val activeServerPingMs: StateFlow<Int?> = connectionStateCoordinator.activePingMs
        .stateIn(viewModelScope, liveReadingSharing(), null)

    /**
     * Keeps the last reading on screen when the banner comes back, rather than dashing every cell
     * until fresh numbers arrive. The service primes its loop so a real reading lands within a
     * fraction of a second, which bounds how long a carried-over rate can be shown.
     */
    private fun liveReadingSharing() = SharingStarted.WhileSubscribed(LIVE_READING_GRACE_MILLIS)

    private val refreshOperations = MutableStateFlow(0)
    val isRefreshing: StateFlow<Boolean> = refreshOperations
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _pendingSubscriptionRouting = MutableStateFlow<SubscriptionRoutingData?>(null)
    val pendingSubscriptionRouting: StateFlow<SubscriptionRoutingData?> = _pendingSubscriptionRouting.asStateFlow()

    /** Server the user picked while an edited active config is stored, pending their confirmation. */
    private val _pendingServerSelection = MutableStateFlow<Long?>(null)
    val pendingServerSelection: StateFlow<Long?> = _pendingServerSelection.asStateFlow()
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
            connectionRuntimeManager.reconcileState()
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
                _uiEvents.send(HomeUiEvent.Toast(context.localizedString(R.string.home_app_update_install_failed)))
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
                _uiEvents.send(HomeUiEvent.Toast(context.localizedString(R.string.home_app_update_install_failed)))
            }
        }
    }

    fun confirmInstallPermissionRationale() {
        viewModelScope.launch {
            try {
                appUpdateInstaller.confirmInstallPermissionRationale()
            } catch (_: Exception) {
                _uiEvents.send(HomeUiEvent.Toast(context.localizedString(R.string.home_app_update_install_failed)))
            }
        }
    }

    fun dismissInstallPermissionRationale() {
        appUpdateInstaller.dismissInstallPermissionRationale()
    }

    fun selectServer(serverId: Long) {
        serverSelectionJob?.cancel()
        serverSelectionJob = viewModelScope.launch {
            // The edited active config was written against the currently selected server, so
            // moving away from it throws the edit away. Say so before it happens.
            if (serverId != settingsRepo.lastServerId.first() && activeConfigOverrideStore.exists()) {
                _pendingServerSelection.value = serverId
                return@launch
            }
            applyServerSelection(serverId)
        }
    }

    fun confirmDiscardEditedActiveConfig() {
        val serverId = _pendingServerSelection.value ?: return
        _pendingServerSelection.value = null
        serverSelectionJob?.cancel()
        serverSelectionJob = viewModelScope.launch {
            activeConfigOverrideStore.clear()
            applyServerSelection(serverId)
        }
    }

    fun dismissDiscardEditedActiveConfig() {
        _pendingServerSelection.value = null
    }

    private suspend fun applyServerSelection(serverId: Long) {
        val selectionChanged = serverSelectionCoordinator.withSelectionLock {
            if (serverId == settingsRepo.lastServerId.first()) return@withSelectionLock false
            val serverEntity = serverRepo.getById(serverId) ?: return@withSelectionLock false
            runCatching { serverRepo.parseConfig(serverEntity) }.getOrNull() ?: return@withSelectionLock false
            settingsRepo.setLastServerId(serverId)
            true
        }
        if (!selectionChanged) return

        delay(SERVER_SELECTION_SETTLE_MILLIS)
        serverSelectionCoordinator.withSelectionLock {
            if (serverId != settingsRepo.lastServerId.first()) return@withSelectionLock
            providerRoutingCoordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)

            val state = connectionState.value
            if (state is ConnectionState.Connected ||
                state is ConnectionState.ApplyingRoutingChanges ||
                state is ConnectionState.Error
            ) {
                routingChangeManager.clearPendingChanges()
                XrayService.switchServer(context)
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
            runSubscriptionOperation {
                subscriptionRepo.add(name, url, preferJson, userAgentMode, customUserAgent, customHeaders)
            }
        }
    }

    fun addLink(link: String) {
        viewModelScope.launch {
            runSubscriptionOperation { subscriptionRepo.addLink(link) }
        }
    }

    fun requestApplySubscriptionRouting(sub: SubscriptionEntity) {
        val routing = sub.manualRoutingData(
            policy = routingPolicyControl.value,
            selectedProvider = providerRoutingAvailability.value,
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
        viewModelScope.launch { subscriptionRefreshCoordinator.deleteSubscription(sub) }
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
                    runSubscriptionOperation {
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
                runSubscriptionOperation {
                    val result = subscriptionRefreshCoordinator.refreshAll()
                    reportBatchRefreshFailures(result.failures)
                }
            }
        }
    }

    fun refreshSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            withRefreshTracking {
                runSubscriptionOperation {
                    subscriptionRefreshCoordinator.refreshSubscription(sub.id, sub.url)
                }
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

    fun onHidden() {
        latencyRunId++
        val canceledServerIds = activeLatencyServerIds
        activeLatencyServerIds = emptySet()
        latencyJob?.cancel()
        latencyJob = null
        latencyByServerId.update { current -> current - canceledServerIds }
    }

    private fun restartLatencyTests(servers: List<ServerEntity>, sortDuringTest: Boolean = false) {
        val runId = ++latencyRunId
        val previouslyActiveServerIds = activeLatencyServerIds
        latencyJob?.cancel()
        val pingMethod = defaultPingMethod.value
        val pingMethods = latencyMethods(pingMethod, showBothLatencyResults.value)
        val targetServers = servers.distinctBy { it.id }
        val targetServerIds = targetServers.map { it.id }.toSet()
        val canceledOnlyServerIds = previouslyActiveServerIds - targetServerIds
        activeLatencyServerIds = targetServerIds

        latencyByServerId.update { current ->
            (current - canceledOnlyServerIds) + targetServers.associate { server ->
                server.id to latencyState(
                    primaryMethod = pingMethod,
                    latencyByMethod = pingMethods.associateWith { LATENCY_TESTING },
                )
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
                primaryMethod = pingMethod,
                methods = pingMethods,
                sortDuringTest = sortDuringTest && settingsRepo.sortOutboundsByLatency.first(),
            )
        }
    }

    private suspend fun runLatencyProbes(
        runId: Long,
        servers: List<ServerEntity>,
        primaryMethod: PingMethod,
        methods: List<PingMethod>,
        sortDuringTest: Boolean,
    ) = supervisorScope {
        val probeJobs = servers.map { server ->
            launch { runLatencyProbe(runId, server, primaryMethod, methods) }
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

    private suspend fun runLatencyProbe(
        runId: Long,
        server: ServerEntity,
        primaryMethod: PingMethod,
        methods: List<PingMethod>,
    ) {
        try {
            val latency = try {
                latencySemaphore.withPermit { measureLatency(server, primaryMethod, methods) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                latencyState(primaryMethod, methods.associateWith { -1 })
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

    private suspend fun measureLatency(
        server: ServerEntity,
        primaryMethod: PingMethod,
        methods: List<PingMethod>,
    ): ServerLatencyState {
        val config = runCatching { serverRepo.parseConfig(server) }.getOrNull()
            ?: return latencyState(primaryMethod, methods.associateWith { -1 })
        val probeUrl = settingsRepo.latencyCheckUrl.first()
        val dnsServers = settingsRepo.dnsServers.first()
        val domesticDnsServers = settingsRepo.domesticDnsServers.first()
        val allowIpv6 = settingsRepo.allowIpv6.first()
        val latencyByMethod = buildMap {
            methods.forEach { method ->
                put(
                    method,
                    serverLatencyTester.measure(
                        server = config,
                        method = method,
                        probeUrl = probeUrl,
                        dnsServers = dnsServers,
                        domesticDnsServers = domesticDnsServers,
                        allowIpv6 = allowIpv6,
                    ).latencyMs,
                )
            }
        }
        return latencyState(primaryMethod, latencyByMethod)
    }

    private suspend fun withRefreshTracking(block: suspend () -> Unit) {
        refreshOperations.update { it + 1 }
        try {
            block()
        } finally {
            refreshOperations.update { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun runSubscriptionOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            _uiEvents.send(HomeUiEvent.Toast(subscriptionFailureMessage(error)))
        }
    }

    private suspend fun reportBatchRefreshFailures(failures: Map<Long, IOException>) {
        if (failures.isEmpty()) return

        val firstFailure = subscriptionFailureMessage(failures.values.first())
        val message = if (failures.size == 1) {
            firstFailure
        } else {
            context.localizedString(
                R.string.home_subscription_refresh_batch_failed,
                failures.size,
                firstFailure,
            )
        }
        _uiEvents.send(HomeUiEvent.Toast(message))
    }

    private fun subscriptionFailureMessage(error: IOException): String = when (error) {
        is SubscriptionFetchException -> when (error.reason) {
            SubscriptionFetchException.Reason.INVALID_URL -> context.localizedString(
                R.string.home_subscription_fetch_failed_invalid_url,
            )

            SubscriptionFetchException.Reason.HTTP_STATUS -> context.localizedString(
                R.string.home_subscription_fetch_failed_http,
                error.statusCode ?: 0,
            )

            SubscriptionFetchException.Reason.INSECURE_TRANSPORT -> context.localizedString(
                R.string.home_subscription_fetch_failed_insecure,
            )

            SubscriptionFetchException.Reason.EMPTY_RESPONSE -> context.localizedString(
                R.string.home_subscription_fetch_failed_empty,
                error.statusCode ?: 0,
            )

            SubscriptionFetchException.Reason.UNSUPPORTED_CONTENT -> context.localizedString(
                R.string.home_subscription_fetch_failed_unsupported,
                error.statusCode ?: 0,
            )
        }

        is SocketTimeoutException -> context.localizedString(R.string.home_subscription_fetch_failed_timeout)
        is UnknownHostException -> context.localizedString(R.string.home_subscription_fetch_failed_dns)
        is SSLException -> context.localizedString(R.string.home_subscription_fetch_failed_tls)
        is ConnectException -> context.localizedString(R.string.home_subscription_fetch_failed_connection)
        else -> context.localizedString(R.string.home_subscription_fetch_failed_network)
    }

    private companion object {
        const val MAX_CONCURRENT_LATENCY_TESTS = 25
        const val LATENCY_SORT_INTERVAL_MILLIS = 750L
        const val LIVE_READING_GRACE_MILLIS = 5_000L
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

internal fun latencyMethods(primaryMethod: PingMethod, showBoth: Boolean): List<PingMethod> = if (showBoth) {
    listOf(PingMethod.Tcping, PingMethod.Httping)
} else {
    listOf(primaryMethod)
}

private fun latencyState(
    primaryMethod: PingMethod,
    latencyByMethod: Map<PingMethod, Int>,
): ServerLatencyState = ServerLatencyState(
    latencyMs = latencyByMethod[primaryMethod] ?: -1,
    method = primaryMethod,
    tcpingLatencyMs = latencyByMethod[PingMethod.Tcping],
    httpingLatencyMs = latencyByMethod[PingMethod.Httping],
)
