package com.material.xray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.material.xray.R
import com.material.xray.core.app.AppInventory
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.core.xray.TunManager
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@AndroidEntryPoint
class XrayService : VpnService() {

    @Inject lateinit var rootShell: RootShell

    @Inject lateinit var appBypassDao: AppBypassDao

    @Inject lateinit var serverRepository: ServerRepository

    @Inject lateinit var settingsRepo: SettingsRepository

    @Inject lateinit var subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository

    @Inject lateinit var connectionStateHolder: ConnectionStateHolder

    @Inject lateinit var logBuffer: LogBuffer

    @Inject lateinit var geoDataManager: GeoDataManager

    @Inject lateinit var appInventory: AppInventory

    private lateinit var connectionManager: ConnectionManager
    private lateinit var xrayLogStreamer: XrayLogStreamer
    private val stateFile by lazy { StateFile(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeConfig: ServerConfig? = null
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var networkReconnectJob: Job? = null
    private var activePhysicalNetwork: PhysicalNetworkSnapshot? = null
    private var processWatchdogJob: Job? = null
    private var processWatchdogPid: Int? = null
    private var processRecoveryJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeUseRootService = true
    private var notificationSettings = NotificationSettings()
    private var notificationMetrics = NotificationMetrics()
    private var previousTrafficSample: TrafficSample? = null
    private var notificationMetricsJob: Job? = null
    private var notificationMetricsIntervalMs = 0
    private var lastNotificationContent: NotificationContent? = null
    private val connectionCommandMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground("Material Xray", "Starting...", showDisconnectAction = false)

        xrayLogStreamer = XrayLogStreamer(filesDir.resolve("xray.log"), logBuffer)
        connectionManager = ConnectionManager(
            context = this,
            shell = rootShell,
            configGenerator = ConfigGenerator(),
            geoDataManager = geoDataManager,
            appBypassDao = appBypassDao,
            subscriptionAppRoutingRepository = subscriptionAppRoutingRepository,
            serverRepository = serverRepository,
            appInventory = appInventory,
            stateHolder = connectionStateHolder,
            log = logBuffer,
            onXrayLogReady = { startLogTail() },
        )

        scope.launch {
            connectionStateHolder.state.drop(1).collect { state ->
                handleStateSideEffects(state)
                updateNotification()
            }
        }

        scope.launch {
            settingsRepo.notificationSettings.collectLatest { settings ->
                notificationSettings = settings
                if (!settings.showTrafficSpeed) {
                    previousTrafficSample = null
                    notificationMetrics = notificationMetrics.copy(proxyBps = null, directBps = null)
                }
                updateNotificationMetricsJob()
                updateNotification()
            }
        }

        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground("Material Xray", "Starting...", showDisconnectAction = false)
                val configJson = intent.getStringExtra(EXTRA_SERVER_CONFIG) ?: return START_NOT_STICKY
                launchConnectionCommand {
                    val config = Json.decodeFromString<ServerConfig>(configJson)
                    activeConfig = config
                    networkReconnectJob?.cancel()
                    stopLogTail()
                    connectWithCurrentSettings(config)
                }
            }
            ACTION_SWITCH_SERVER -> {
                startAsForeground("Material Xray", "Switching server...", showDisconnectAction = false)
                val configJson = intent.getStringExtra(EXTRA_SERVER_CONFIG) ?: return START_NOT_STICKY
                launchConnectionCommand {
                    val config = Json.decodeFromString<ServerConfig>(configJson)
                    activeConfig = config
                    networkReconnectJob?.cancel()
                    stopLogTail()
                    stopProcessWatchdog()
                    logBuffer.append(LogSource.APP, "Switching to ${config.name}...")
                    updateNotification("Switching server...")
                    connectionManager.disconnect(updateState = false, fastRootCleanup = true)
                    closeVpnInterface()
                    connectWithCurrentSettings(
                        config = config,
                        transitionState = ConnectionState.ApplyingRoutingChanges,
                        cleanStateFirst = false,
                        fastReconnect = true,
                    )
                }
            }
            ACTION_DISCONNECT -> {
                launchConnectionCommand {
                    activeConfig = null
                    activePhysicalNetwork = null
                    networkReconnectJob?.cancel()
                    stopLogTail()
                    stopProcessWatchdog()
                    connectionManager.disconnect()
                    closeVpnInterface()
                    stopSelf()
                }
            }
            ACTION_RELOAD -> {
                launchConnectionCommand { reloadActiveConnection() }
            }
            ACTION_RELOAD_APP_ROUTING -> {
                launchConnectionCommand { reloadAppRouting() }
            }
            ACTION_RESTORE_STATUS -> {
                launchConnectionCommand { restoreRunningConnectionStatus() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        activeConfig = null
        activePhysicalNetwork = null
        networkReconnectJob?.cancel()
        processRecoveryJob?.cancel()
        stopProcessWatchdog()
        stopLogTail()
        scope.cancel()
        runBlocking {
            connectionManager.disconnect(updateState = false)
        }
        closeVpnInterface()
        rootShell.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startLogTail() = xrayLogStreamer.start(scope)

    private fun stopLogTail() = xrayLogStreamer.stop()

    private fun launchConnectionCommand(block: suspend () -> Unit) {
        scope.launch {
            connectionCommandMutex.withLock {
                block()
            }
        }
    }

    private suspend fun connectWithCurrentSettings(config: ServerConfig) {
        connectWithCurrentSettings(config, ConnectionState.Connecting)
    }

    private suspend fun connectWithCurrentSettings(
        config: ServerConfig,
        transitionState: ConnectionState = ConnectionState.Connecting,
        cleanStateFirst: Boolean = true,
        fastReconnect: Boolean = false,
    ) {
        getSystemService(NotificationManager::class.java).cancel(FAILURE_NOTIFICATION_ID)
        var attempt = 1
        var lastError: String? = null

        while (attempt <= CONNECTION_MAX_ATTEMPTS && currentCoroutineContext().isActive) {
            if (attempt > 1) {
                val retryMessage = "Retrying connection ($attempt/$CONNECTION_MAX_ATTEMPTS)..."
                logBuffer.append(LogSource.APP, retryMessage)
                connectionStateHolder.update(transitionState)
                updateNotification(retryMessage)
            }

            val connected = connectOnceWithCurrentSettings(
                config = config,
                transitionState = transitionState,
                cleanStateFirst = cleanStateFirst || attempt > 1,
                fastReconnect = fastReconnect && attempt == 1,
            )
            if (connected) return

            lastError = (connectionStateHolder.state.value as? ConnectionState.Error)?.message
                ?: "Unknown connection error"
            if (!lastError.shouldRetryConnection() || attempt == CONNECTION_MAX_ATTEMPTS) break

            delay(CONNECTION_RETRY_DELAY_MS)
            attempt++
        }

        showConnectionFailureNotification(lastError ?: "Unknown connection error")
    }

    private suspend fun connectOnceWithCurrentSettings(
        config: ServerConfig,
        transitionState: ConnectionState = ConnectionState.Connecting,
        cleanStateFirst: Boolean = true,
        fastReconnect: Boolean = false,
    ): Boolean {
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        val rootServiceAvailable = if (runtimeSettings.useRootService) {
            withContext(Dispatchers.IO) { rootShell.open() }
        } else {
            false
        }
        if (runtimeSettings.useRootService && !rootServiceAvailable) {
            settingsRepo.setUseRootService(false)
            connectionStateHolder.emitEvent(ConnectionEvent.RootUnavailableFallback)
        }
        val effectiveRuntimeSettings = if (runtimeSettings.useRootService && !rootServiceAvailable) {
            runtimeSettings.copy(useRootService = false)
        } else if (!runtimeSettings.useRootService) {
            runtimeSettings.copy(tunName = ROOTLESS_TUN_NAME)
        } else {
            runtimeSettings
        }
        activeUseRootService = effectiveRuntimeSettings.useRootService
        val activeVpnInterface = if (effectiveRuntimeSettings.useRootService) {
            closeVpnInterface()
            null
        } else {
            setupVpnInterface(effectiveRuntimeSettings) ?: return false
        }
        connectionManager.connect(
            server = config,
            runtimeSettings = effectiveRuntimeSettings,
            vpnInterface = activeVpnInterface,
            transitionState = transitionState,
            cleanStateFirst = cleanStateFirst,
            fastReconnect = fastReconnect,
        )
        if (connectionStateHolder.state.value is ConnectionState.Connected && effectiveRuntimeSettings.useRootService) {
            activePhysicalNetwork = currentPhysicalNetworkSnapshot()
        } else if (!effectiveRuntimeSettings.useRootService) {
            activePhysicalNetwork = null
        }
        return connectionStateHolder.state.value is ConnectionState.Connected
    }

    private suspend fun reloadActiveConnection() {
        val config = activeConfig ?: return
        networkReconnectJob?.cancel()
        stopLogTail()
        stopProcessWatchdog()
        logBuffer.append(LogSource.APP, "Applying routing changes...")
        connectionStateHolder.update(ConnectionState.ApplyingRoutingChanges)
        updateNotification()
        connectionManager.disconnect(updateState = false, fastRootCleanup = true)
        closeVpnInterface()
        connectWithCurrentSettings(config, ConnectionState.ApplyingRoutingChanges, cleanStateFirst = false)
    }

    private suspend fun reloadAppRouting() {
        val config = activeConfig ?: return
        val connectedState = connectionStateHolder.state.value as? ConnectionState.Connected
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        if (connectedState == null) {
            reloadActiveConnection()
            return
        }
        if (!runtimeSettings.useRootService) {
            reloadActiveConnection()
            return
        }

        networkReconnectJob?.cancel()
        logBuffer.append(LogSource.APP, "Applying app routing changes...")
        connectionStateHolder.update(ConnectionState.ApplyingRoutingChanges)
        updateNotification()

        val fastApplied = connectionManager.applyAppRoutingChanges(
            connectedState = connectedState,
            runtimeSettings = runtimeSettings,
        )
        if (fastApplied) {
            connectionStateHolder.update(connectedState)
            return
        }

        stopLogTail()
        stopProcessWatchdog()
        logBuffer.append(LogSource.APP, "Restarting Xray to apply app routing topology changes...")
        connectionManager.disconnect(updateState = false, fastRootCleanup = true)
        connectWithCurrentSettings(config, ConnectionState.ApplyingRoutingChanges, cleanStateFirst = false)
    }

    private suspend fun restoreRunningConnectionStatus() {
        val alreadyConnected = connectionStateHolder.state.value as? ConnectionState.Connected
        if (alreadyConnected != null && activeConfig != null) {
            activePhysicalNetwork = currentPhysicalNetworkSnapshot()
            handleStateSideEffects(alreadyConnected)
            updateNotification()
            return
        }

        val restoredState = detectRestorableRunningConnection()
        if (restoredState == null) {
            logBuffer.append(LogSource.APP, "No restorable running Xray state was found")
            if (connectionStateHolder.state.value !is ConnectionState.Connected) {
                connectionStateHolder.update(ConnectionState.Disconnected)
                updateNotification()
                stopSelf()
            }
            return
        }

        activeConfig = loadLastServerConfig()
        if (activeConfig == null) {
            logBuffer.append(LogSource.APP, "Restored running status without selected server config")
        }
        connectionStateHolder.update(restoredState)
        activePhysicalNetwork = currentPhysicalNetworkSnapshot()
        handleStateSideEffects(restoredState)
        updateNotification()
    }

    private suspend fun detectRestorableRunningConnection(): ConnectionState.Connected? = withContext(Dispatchers.IO) {
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        if (!runtimeSettings.useRootService) return@withContext null

        val state = stateFile.read() ?: return@withContext null
        if (state.xrayPid <= 0) return@withContext null
        if (!connectionManager.isProcessAlive(state.xrayPid)) return@withContext null
        if (!TunInterfaceDetector.isInterfaceUp(state.tunName)) return@withContext null

        val currentRoute = connectionManager.detectPhysicalRoute(state.tunName)
        ConnectionState.Connected(
            serverName = state.serverName.takeIf { it.isNotBlank() } ?: "Selected server",
            corePid = state.xrayPid,
            tunName = state.tunName,
            physicalInterface = currentRoute?.dev ?: state.physicalInterface ?: "unknown",
            physicalGateway = currentRoute?.gateway ?: state.physicalGateway,
            physicalTable = currentRoute?.table ?: state.physicalTable,
            startTime = state.timestamp,
        )
    }

    private suspend fun loadLastServerConfig(): ServerConfig? {
        val lastServerId = settingsRepo.lastServerId.first()
        if (lastServerId < 0) return null
        val serverEntity = serverRepository.getById(lastServerId) ?: return null
        return runCatching { serverRepository.parseConfig(serverEntity) }.getOrNull()
    }

    private fun handleStateSideEffects(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> startProcessWatchdog(state)
            else -> {
                stopProcessWatchdog()
                stopNotificationMetrics()
            }
        }
        updateNotificationMetricsJob()
    }

    private fun startProcessWatchdog(state: ConnectionState.Connected) {
        if (processWatchdogPid == state.corePid && processWatchdogJob?.isActive == true) return

        stopProcessWatchdog()
        processWatchdogPid = state.corePid
        processWatchdogJob = scope.launch(Dispatchers.IO) {
            var keepWatching = true
            while (isActive && keepWatching) {
                delay(PROCESS_WATCHDOG_INTERVAL_MS)
                keepWatching = checkXrayProcessHealth()
            }
        }
    }

    private suspend fun checkXrayProcessHealth(): Boolean {
        val currentState = connectionStateHolder.state.value as? ConnectionState.Connected ?: return false
        val pid = currentState.corePid
        if (!connectionManager.isProcessAlive(pid)) {
            recoverNativeProcess(
                reason = "xray process $pid exited unexpectedly; reconnecting...",
            )
            return false
        }

        val residentMemoryMb = connectionManager.readProcessResidentMemoryMb(pid) ?: return true
        if (residentMemoryMb > MAX_XRAY_PROCESS_MEMORY_MB) {
            recoverNativeProcess(
                reason = "xray process $pid exceeded ${MAX_XRAY_PROCESS_MEMORY_MB} MiB RSS ($residentMemoryMb MiB); restarting...",
                pidToKill = pid,
            )
            return false
        }
        return true
    }

    private fun stopProcessWatchdog() {
        processWatchdogJob?.cancel()
        processWatchdogJob = null
        processWatchdogPid = null
    }

    private fun updateNotificationMetricsJob() {
        val state = connectionStateHolder.state.value
        if (state is ConnectionState.Connected && notificationSettings.hasDynamicMetrics) {
            startNotificationMetrics(state)
        } else {
            stopNotificationMetrics()
        }
    }

    private fun startNotificationMetrics(state: ConnectionState.Connected) {
        val intervalMs = notificationSettings.updateIntervalMs
        if (notificationMetricsJob?.isActive == true && notificationMetricsIntervalMs == intervalMs) return

        stopNotificationMetrics()
        notificationMetricsIntervalMs = intervalMs
        previousTrafficSample = null
        notificationMetricsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val connectedState = connectionStateHolder.state.value as? ConnectionState.Connected ?: break
                val metrics = readNotificationMetrics(connectedState)
                if (metrics != notificationMetrics) {
                    withContext(Dispatchers.Main) {
                        notificationMetrics = metrics
                        updateNotification()
                    }
                }
                delay(notificationSettings.updateIntervalMs.toLong())
            }
        }
        if (state.corePid <= 0) stopNotificationMetrics()
    }

    private fun stopNotificationMetrics() {
        notificationMetricsJob?.cancel()
        notificationMetricsJob = null
        notificationMetricsIntervalMs = 0
        previousTrafficSample = null
        notificationMetrics = NotificationMetrics()
    }

    private suspend fun readNotificationMetrics(state: ConnectionState.Connected): NotificationMetrics {
        val settings = notificationSettings
        val trafficSpeeds = if (settings.showTrafficSpeed) {
            readTrafficSpeeds()
        } else {
            null
        }
        val ramMb = if (settings.showRamUsage) {
            connectionManager.readProcessResidentMemoryMb(state.corePid)
        } else {
            null
        }
        val connectionCount = if (settings.showConnectionCount) {
            connectionManager.readActiveConnectionCount(state.corePid)
        } else {
            null
        }
        return NotificationMetrics(
            proxyBps = trafficSpeeds?.proxyBps,
            directBps = trafficSpeeds?.directBps,
            ramMb = ramMb,
            connectionCount = connectionCount,
        )
    }

    private suspend fun readTrafficSpeeds(): TrafficSpeeds? {
        val stats = connectionManager.readOutboundTrafficStatsBytes()
        val hasProxyStats = stats.hasOutboundTrafficStats("proxy")
        val hasDirectStats = stats.hasOutboundTrafficStats("direct")
        if (!hasProxyStats && !hasDirectStats) {
            previousTrafficSample = null
            return null
        }
        val now = System.currentTimeMillis()
        val sample = TrafficSample(
            timestampMs = now,
            proxyBytes = stats.outboundBytes("proxy"),
            directBytes = stats.outboundBytes("direct"),
        )
        val previous = previousTrafficSample.also { previousTrafficSample = sample } ?: return null
        val elapsedSeconds = ((sample.timestampMs - previous.timestampMs).coerceAtLeast(1)).toDouble() / 1000.0
        val proxyDelta = (sample.proxyBytes - previous.proxyBytes).coerceAtLeast(0)
        val directDelta = (sample.directBytes - previous.directBytes).coerceAtLeast(0)
        return TrafficSpeeds(
            proxyBps = (proxyDelta / elapsedSeconds).toLong(),
            directBps = (directDelta / elapsedSeconds).toLong(),
        )
    }

    private fun recoverNativeProcess(
        reason: String,
        pidToKill: Int? = null,
    ) {
        if (processRecoveryJob?.isActive == true) return

        processRecoveryJob = scope.launch {
            connectionCommandMutex.withLock {
                val config = activeConfig ?: return@withLock

                networkReconnectJob?.cancel()
                stopLogTail()
                stopProcessWatchdog()
                logBuffer.append(LogSource.APP, reason)

                if (pidToKill != null) {
                    connectionManager.killProcess(pidToKill, signal = 9)
                }

                connectionStateHolder.update(ConnectionState.Connecting)
                updateNotification("Recovering native core...")
                connectionManager.disconnect(updateState = false, fastRootCleanup = true)
                delay(PROCESS_RESTART_DELAY_MS)
                connectWithCurrentSettings(config, cleanStateFirst = false)
            }
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        registerNetworkWatcher("default") { callback ->
            connectivityManager.registerDefaultNetworkCallback(callback)
        }

        val physicalNetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        registerNetworkWatcher("physical") { callback ->
            connectivityManager.registerNetworkCallback(physicalNetworkRequest, callback)
        }
    }

    private fun registerNetworkWatcher(
        source: String,
        register: (ConnectivityManager.NetworkCallback) -> Unit,
    ) {
        val callback = networkCallback(source)
        runCatching {
            register(callback)
            networkCallbacks += callback
        }.onFailure { error ->
            logBuffer.append(LogSource.APP, "Could not watch $source network changes: ${error.message}")
        }
    }

    private fun networkCallback(source: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleNetworkRetarget("$source available")
        }

        override fun onLost(network: Network) {
            scheduleNetworkRetarget("$source lost")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleNetworkRetarget("$source capabilities changed")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            scheduleNetworkRetarget("$source link properties changed")
        }
    }

    private fun unregisterNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallbacks.forEach { callback ->
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
        networkCallbacks.clear()
    }

    private fun scheduleNetworkRetarget(reason: String) {
        if (!activeUseRootService) return
        activeConfig ?: return
        connectionStateHolder.state.value as? ConnectionState.Connected ?: return

        networkReconnectJob?.cancel()
        networkReconnectJob = scope.launch {
            delay(NETWORK_RETARGET_DEBOUNCE_MS)
            retargetNetworkUntilStable(reason)
        }
    }

    private suspend fun retargetNetworkUntilStable(reason: String) {
        var attempt = 1
        while (attempt <= NETWORK_RETARGET_MAX_ATTEMPTS && currentCoroutineContext().isActive) {
            when (retargetNetwork(reason, attempt)) {
                NetworkRetargetResult.Done -> return
                NetworkRetargetResult.Retry -> {
                    attempt++
                    if (attempt <= NETWORK_RETARGET_MAX_ATTEMPTS) {
                        delay(NETWORK_RETARGET_RETRY_DELAY_MS)
                    }
                }
            }
        }
        logBuffer.append(LogSource.APP, "Network changed ($reason), but no usable physical route appeared")
        updateNotification("Waiting for physical route")
    }

    private suspend fun retargetNetwork(reason: String, attempt: Int): NetworkRetargetResult = connectionCommandMutex.withLock {
        val latestConfig = activeConfig ?: return@withLock NetworkRetargetResult.Done
        val latestState = connectionStateHolder.state.value as? ConnectionState.Connected
            ?: return@withLock NetworkRetargetResult.Done
        val previousNetwork = activePhysicalNetwork
        val currentNetwork = currentPhysicalNetworkSnapshot()
        val currentRoute = withContext(Dispatchers.IO) {
            connectionManager.detectPhysicalRoute(latestState.tunName)
        }

        if (currentRoute == null) {
            if (attempt == 1) {
                logBuffer.append(LogSource.APP, "Network changed ($reason), waiting for a usable physical route")
            }
            updateNotification("Waiting for physical route")
            return@withLock NetworkRetargetResult.Retry
        }

        val androidNetworkChanged = previousNetwork != null &&
            currentNetwork != null &&
            !previousNetwork.sameNetwork(currentNetwork)
        val physicalRouteChanged = !currentRoute.matches(latestState)
        if (previousNetwork != null && currentNetwork == null) {
            if (attempt == 1) {
                logBuffer.append(LogSource.APP, "Network changed ($reason), waiting for an active physical network")
            }
            updateNotification("Waiting for physical network")
            return@withLock NetworkRetargetResult.Retry
        }

        if (!androidNetworkChanged && !physicalRouteChanged) {
            activePhysicalNetwork = currentNetwork ?: previousNetwork
            updateNotification()
            return@withLock NetworkRetargetResult.Done
        }

        if (androidNetworkChanged || currentRoute.dev != latestState.physicalInterface) {
            logBuffer.append(
                LogSource.APP,
                "Network changed ($reason): ${describeNetworkChange(previousNetwork, currentNetwork)}, " +
                    "${latestState.physicalInterface} -> ${currentRoute.describe()}, reconnecting...",
            )
            reconnectForPhysicalRouteChange(latestConfig, latestState, currentRoute)
            return@withLock NetworkRetargetResult.Done
        }

        logBuffer.append(
            LogSource.APP,
            "Network route changed ($reason): ${latestState.describePhysicalRoute()} -> " +
                "${currentRoute.describe()}, refreshing routing...",
        )
        updateNotification("Refreshing physical route")
        when (
            val result = withContext(Dispatchers.IO) {
                connectionManager.reapplyPhysicalRoutingForNetworkChange(
                    connectedState = latestState,
                    runtimeSettings = settingsRepo.runtimeSettingsSnapshot(),
                )
            }
        ) {
            is PhysicalRouteUpdateResult.Applied -> {
                connectionStateHolder.update(
                    latestState.copy(
                        physicalInterface = result.route.dev,
                        physicalGateway = result.route.gateway,
                        physicalTable = result.route.table,
                    ),
                )
                activePhysicalNetwork = currentPhysicalNetworkSnapshot() ?: currentNetwork ?: previousNetwork
                updateNotification()
                NetworkRetargetResult.Done
            }
            PhysicalRouteUpdateResult.RouteUnavailable -> {
                updateNotification("Waiting for physical route")
                NetworkRetargetResult.Retry
            }
            PhysicalRouteUpdateResult.RequiresReconnect -> {
                reconnectForPhysicalRouteChange(latestConfig, latestState, currentRoute)
                NetworkRetargetResult.Done
            }
        }
    }

    private suspend fun reconnectForPhysicalRouteChange(
        config: ServerConfig,
        previousState: ConnectionState.Connected,
        currentRoute: TunManager.PhysicalRoute,
    ) {
        updateNotification("Pinning ${previousState.tunName} to ${currentRoute.dev}")
        stopLogTail()
        stopProcessWatchdog()
        connectionStateHolder.update(ConnectionState.Connecting)
        connectionManager.disconnect(updateState = false, fastRootCleanup = true)
        closeVpnInterface()
        connectWithCurrentSettings(config, cleanStateFirst = false)
    }

    private suspend fun setupVpnInterface(runtimeSettings: XrayRuntimeSettings): ParcelFileDescriptor? {
        if (prepare(this) != null) {
            connectionStateHolder.update(ConnectionState.Error(VPN_PERMISSION_REQUIRED_ERROR))
            stopSelf()
            return null
        }

        val builder = Builder()
            .setSession("Material Xray")
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)

        runtimeSettings.dnsServers
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any(Char::isDigit) }
            .forEach { dnsServer ->
                runCatching { builder.addDnsServer(dnsServer) }
            }

        addDisallowedPackage(builder, packageName, ignoreMissing = true)
        val appRouteAssignments = withContext(Dispatchers.IO) { appBypassDao.getAll() }
        val bypassPackages = appRouteAssignments
            .filter { entity ->
                when (entity.routeAssignment()) {
                    AppRouteAssignment(AppRouteMode.Direct),
                    AppRouteAssignment(AppRouteMode.Bypass),
                    -> true
                    else -> false
                }
            }
            .map { it.packageName }
            .distinct()
        val rootOnlyRouteCount = appRouteAssignments.count { entity ->
            val routeMode = entity.routeAssignment().mode
            routeMode == AppRouteMode.Server || routeMode == AppRouteMode.DefaultOutbound
        }

        bypassPackages.forEach { packageName ->
            addDisallowedPackage(builder, packageName, ignoreMissing = false)
        }
        if (bypassPackages.isNotEmpty()) {
            logBuffer.append(LogSource.APP, "Rootless app routing: ${bypassPackages.size} app package(s) bypass VPN")
        }
        if (rootOnlyRouteCount > 0) {
            logBuffer.append(
                LogSource.APP,
                "Rootless app routing: $rootOnlyRouteCount root-only app route(s) use the default selected server",
            )
        }

        closeVpnInterface()
        return runCatching { builder.establish() ?: error("Could not establish VPN interface") }
            .onSuccess { descriptor ->
                vpnInterface = descriptor
                logBuffer.append(LogSource.APP, "Android VPN interface established")
            }
            .onFailure { error ->
                connectionStateHolder.update(ConnectionState.Error(error.message ?: "Could not establish VPN interface"))
            }
            .getOrNull()
    }

    private fun addDisallowedPackage(builder: Builder, packageName: String, ignoreMissing: Boolean) {
        runCatching {
            builder.addDisallowedApplication(packageName)
        }.onFailure { error ->
            if (error !is PackageManager.NameNotFoundException || !ignoreMissing) {
                logBuffer.append(LogSource.APP, "Could not exclude $packageName from VPN: ${error.message}")
            }
        }
    }

    private fun closeVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun onRevoke() {
        launchConnectionCommand {
            activeConfig = null
            activePhysicalNetwork = null
            networkReconnectJob?.cancel()
            stopLogTail()
            stopProcessWatchdog()
            connectionManager.disconnect()
            closeVpnInterface()
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentPhysicalNetworkSnapshot(): PhysicalNetworkSnapshot? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.activeNetwork
            ?.toPhysicalNetworkSnapshot(connectivityManager)
            ?.let { return it }

        return connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { network -> network.toPhysicalNetworkSnapshot(connectivityManager) }
            .sortedWith(
                compareByDescending<PhysicalNetworkSnapshot> { it.validated }
                    .thenBy { it.priority },
            )
            .firstOrNull()
    }

    private fun Network.toPhysicalNetworkSnapshot(
        connectivityManager: ConnectivityManager,
    ): PhysicalNetworkSnapshot? {
        val capabilities = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val transports = capabilities.physicalTransports()
        if (transports.isEmpty()) return null

        return PhysicalNetworkSnapshot(
            handle = networkHandle,
            label = transports.joinToString("+"),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            priority = transports.minOf(::transportPriority),
        )
    }

    private fun NetworkCapabilities.physicalTransports(): List<String> = buildList {
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(TRANSPORT_LABEL_WIFI)
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(TRANSPORT_LABEL_ETHERNET)
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(TRANSPORT_LABEL_CELLULAR)
    }

    private fun transportPriority(label: String): Int = when (label) {
        TRANSPORT_LABEL_WIFI -> 0
        TRANSPORT_LABEL_ETHERNET -> 1
        TRANSPORT_LABEL_CELLULAR -> 2
        else -> 3
    }

    private fun TunManager.PhysicalRoute.matches(state: ConnectionState.Connected): Boolean = dev == state.physicalInterface &&
        gateway == state.physicalGateway &&
        table == state.physicalTable

    private fun ConnectionState.Connected.describePhysicalRoute(): String = buildString {
        append(physicalInterface)
        if (!physicalGateway.isNullOrBlank()) append(" via $physicalGateway")
        if (!physicalTable.isNullOrBlank()) append(" table $physicalTable")
    }

    private fun TunManager.PhysicalRoute.describe(): String = buildString {
        append(dev)
        if (!gateway.isNullOrBlank()) append(" via $gateway")
        if (!table.isNullOrBlank()) append(" table $table")
    }

    private fun describeNetworkChange(
        previousNetwork: PhysicalNetworkSnapshot?,
        currentNetwork: PhysicalNetworkSnapshot?,
    ): String = when {
        previousNetwork != null && currentNetwork != null ->
            "${previousNetwork.describe()} -> ${currentNetwork.describe()}"
        previousNetwork != null -> "${previousNetwork.describe()} -> unknown"
        currentNetwork != null -> "unknown -> ${currentNetwork.describe()}"
        else -> "active physical network changed"
    }

    private fun updateNotification(overrideText: String? = null) {
        XrayTileService.requestStateRefresh(this)
        val state = connectionStateHolder.state.value
        if (state is ConnectionState.Disconnected) {
            lastNotificationContent = null
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val title = when (state) {
            is ConnectionState.Connected -> state.serverName
            else -> "Material Xray"
        }
        val text = overrideText ?: when (state) {
            is ConnectionState.Connected -> connectedNotificationText(state)
            is ConnectionState.Connecting -> "Connecting..."
            ConnectionState.ApplyingRoutingChanges -> "Applying routing changes..."
            ConnectionState.UpdatingRoutingData -> "Updating routing data..."
            is ConnectionState.RestartRequired -> "Restart required"
            is ConnectionState.InterfaceBusy -> "Interface busy"
            is ConnectionState.Disconnecting -> "Disconnecting..."
            is ConnectionState.Error -> "Error: ${state.message}"
            ConnectionState.Disconnected -> return
        }
        val showDisconnectAction = state !is ConnectionState.Error
        val content = NotificationContent(title, text, showDisconnectAction)
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text, showDisconnectAction))
    }

    private fun showConnectionFailureNotification(message: String) {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, FAILURE_CHANNEL_ID)
            .setContentTitle("Connection failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_launcher_default_monochrome)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(FAILURE_NOTIFICATION_ID, notification)
    }

    private fun connectedNotificationText(state: ConnectionState.Connected): String {
        val baseText = if (state.physicalInterface == VPN_SERVICE_INTERFACE_LABEL) {
            "VPN service active"
        } else {
            "Root service active (pinned ${state.tunName} to ${state.physicalInterface})"
        }
        val settings = notificationSettings
        if (!settings.enabled || !settings.anyFieldEnabled) return baseText

        val metrics = notificationMetrics
        val separator = if (settings.style == NotificationStyle.Compact) " • " else "\n"
        return settings.normalizedFieldOrder()
            .mapNotNull { field -> notificationFieldText(field, settings, metrics) }
            .joinToString(separator)
            .ifBlank { baseText }
    }

    private fun notificationFieldText(
        field: NotificationField,
        settings: NotificationSettings,
        metrics: NotificationMetrics,
    ): String? {
        if (!settings.isFieldEnabled(field)) return null
        val compact = settings.style == NotificationStyle.Compact
        return when (field) {
            NotificationField.TrafficSpeed -> {
                val proxy = formatNullableBytesPerSecond(metrics.proxyBps)
                val direct = formatNullableBytesPerSecond(metrics.directBps)
                if (compact) "P $proxy / D $direct" else "Proxy: $proxy | Direct: $direct"
            }
            NotificationField.RamUsage -> {
                val ram = metrics.ramMb?.let { "$it MiB" } ?: "--"
                if (compact) "RAM $ram" else "Core RAM: $ram"
            }
            NotificationField.ConnectionCount -> {
                val count = metrics.connectionCount?.toString() ?: "--"
                if (compact) "Conn $count" else "Connections: $count"
            }
        }
    }

    private fun Map<String, Long>.outboundBytes(tag: String): Long = get("outbound>>>$tag>>>traffic>>>uplink").orZero() +
        get("outbound>>>$tag>>>traffic>>>downlink").orZero()

    private fun Map<String, Long>.hasOutboundTrafficStats(tag: String): Boolean = containsKey("outbound>>>$tag>>>traffic>>>uplink") ||
        containsKey("outbound>>>$tag>>>traffic>>>downlink")

    private fun Long?.orZero(): Long = this ?: 0L

    private fun formatNullableBytesPerSecond(bytesPerSecond: Long?): String = bytesPerSecond?.let(::formatBytesPerSecond) ?: "--"

    private fun formatBytesPerSecond(bytesPerSecond: Long): String {
        val units = listOf("B/s", "KiB/s", "MiB/s", "GiB/s")
        var value = bytesPerSecond.coerceAtLeast(0).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "%.1f %s".format(java.util.Locale.US, value, units[unitIndex])
        }
    }

    private fun startAsForeground(title: String, text: String, showDisconnectAction: Boolean) {
        lastNotificationContent = NotificationContent(title, text, showDisconnectAction)
        startForeground(NOTIFICATION_ID, buildNotification(title, text, showDisconnectAction))
    }

    private fun buildNotification(title: String, text: String, showDisconnectAction: Boolean): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, XrayService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_default_monochrome)
            .setContentIntent(openIntent)
            .setShowWhen(false)
            .setWhen(0)
            .setUsesChronometer(false)
            .setOnlyAlertOnce(true)
            .setOngoing(showDisconnectAction)

        if (showDisconnectAction) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Xray Service", NotificationManager.IMPORTANCE_LOW)
        val failureChannel = NotificationChannel(
            FAILURE_CHANNEL_ID,
            "Connection failures",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(failureChannel)
        }
    }

    private fun String.shouldRetryConnection(): Boolean = this != VPN_PERMISSION_REQUIRED_ERROR

    private data class PhysicalNetworkSnapshot(
        val handle: Long,
        val label: String,
        val validated: Boolean,
        val priority: Int,
    ) {
        fun sameNetwork(other: PhysicalNetworkSnapshot): Boolean = handle == other.handle

        fun describe(): String = "$label#$handle" + if (validated) " validated" else " unvalidated"
    }

    private data class NotificationMetrics(
        val proxyBps: Long? = null,
        val directBps: Long? = null,
        val ramMb: Long? = null,
        val connectionCount: Int? = null,
    )

    private data class NotificationContent(
        val title: String,
        val text: String,
        val showDisconnectAction: Boolean,
    )

    private data class TrafficSample(
        val timestampMs: Long,
        val proxyBytes: Long,
        val directBytes: Long,
    )

    private data class TrafficSpeeds(
        val proxyBps: Long,
        val directBps: Long,
    )

    private enum class NetworkRetargetResult {
        Done,
        Retry,
    }

    companion object {
        const val CHANNEL_ID = "xray_service"
        const val FAILURE_CHANNEL_ID = "xray_connection_failures"
        const val NOTIFICATION_ID = 1
        const val FAILURE_NOTIFICATION_ID = 2
        const val ACTION_CONNECT = "com.material.xray.CONNECT"
        const val ACTION_SWITCH_SERVER = "com.material.xray.SWITCH_SERVER"
        const val ACTION_DISCONNECT = "com.material.xray.DISCONNECT"
        const val ACTION_RELOAD = "com.material.xray.RELOAD"
        const val ACTION_RELOAD_APP_ROUTING = "com.material.xray.RELOAD_APP_ROUTING"
        const val ACTION_RESTORE_STATUS = "com.material.xray.RESTORE_STATUS"
        const val EXTRA_SERVER_CONFIG = "server_config"
        private const val NETWORK_RETARGET_DEBOUNCE_MS = 1_500L
        private const val NETWORK_RETARGET_RETRY_DELAY_MS = 2_000L
        private const val NETWORK_RETARGET_MAX_ATTEMPTS = 30
        private const val CONNECTION_MAX_ATTEMPTS = 3
        private const val CONNECTION_RETRY_DELAY_MS = 1_500L
        private const val PROCESS_RESTART_DELAY_MS = 2_000L
        private const val PROCESS_WATCHDOG_INTERVAL_MS = 10_000L
        private const val MAX_XRAY_PROCESS_MEMORY_MB = 512L
        private const val VPN_PERMISSION_REQUIRED_ERROR = "VPN permission is required"
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.10.14.1"
        private const val VPN_PREFIX_LENGTH = 30
        private const val ROOTLESS_TUN_NAME = "tun0"
        private const val VPN_SERVICE_INTERFACE_LABEL = "VpnService"
        private const val TRANSPORT_LABEL_WIFI = "wifi"
        private const val TRANSPORT_LABEL_ETHERNET = "ethernet"
        private const val TRANSPORT_LABEL_CELLULAR = "cellular"

        fun connect(context: Context, serverConfig: ServerConfig) {
            val intent = Intent(context, XrayService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_CONFIG, Json.encodeToString(ServerConfig.serializer(), serverConfig))
            }
            context.startForegroundService(intent)
        }

        fun switchServer(context: Context, serverConfig: ServerConfig) {
            val intent = Intent(context, XrayService::class.java).apply {
                action = ACTION_SWITCH_SERVER
                putExtra(EXTRA_SERVER_CONFIG, Json.encodeToString(ServerConfig.serializer(), serverConfig))
            }
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(ACTION_DISCONNECT),
            )
        }

        fun reload(context: Context) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(ACTION_RELOAD),
            )
        }

        fun reloadAppRouting(context: Context) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(ACTION_RELOAD_APP_ROUTING),
            )
        }

        fun restoreStatus(context: Context) {
            context.startForegroundService(
                Intent(context, XrayService::class.java).setAction(ACTION_RESTORE_STATUS),
            )
        }
    }
}
