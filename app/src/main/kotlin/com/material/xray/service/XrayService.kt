package com.material.xray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.material.xray.R
import com.material.xray.core.locale.appLocaleChanges
import com.material.xray.core.locale.forAppLanguage
import com.material.xray.core.locale.localizedString
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayState
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.repository.ProviderRoutingActiveUpdate
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.model.primaryBalancerTag
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Suppress("LargeClass")
@AndroidEntryPoint
class XrayService : VpnService() {

    @Inject lateinit var rootShell: RootShell

    @Inject lateinit var appBypassDao: AppBypassDao

    @Inject lateinit var serverRepository: ServerRepository

    @Inject lateinit var settingsRepo: SettingsRepository

    @Inject lateinit var connectionStateCoordinator: ConnectionStateCoordinator

    @Inject lateinit var alwaysOnVpnState: AlwaysOnVpnState

    @Inject lateinit var logBuffer: LogBuffer

    @Inject lateinit var connectionManagerFactory: ConnectionManagerFactory

    @Inject lateinit var providerRoutingCoordinator: ProviderRoutingCoordinator

    private lateinit var connectionManager: ConnectionManager
    private lateinit var xrayLogStreamer: XrayLogStreamer
    private val stateFile by lazy { StateFile(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeConfig: ServerConfig? = null
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private lateinit var networkRetargetWorker: NetworkRetargetWorker
    private var activePhysicalNetwork: PhysicalNetworkSnapshot? = null
    private var networkCallbacksAvailable = false
    private var lastNetworkSafetyCheckAtMs = 0L
    private var processWatchdogJob: Job? = null
    private var processWatchdogPid: Int? = null
    private var processRecoveryJob: Job? = null
    private var alwaysOnRetryJob: Job? = null

    @Volatile
    private var xrayMemoryRestartThresholdMiB = XrayRuntimeSettings.DEFAULT_XRAY_MEMORY_RESTART_THRESHOLD_MIB
    private var balancerSelectionJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeUseRootService = true
    private var notificationSettings = NotificationSettings()
    private var notificationMetrics = NotificationMetrics()
    private var previousTrafficSample: TrafficSample? = null
    private var notificationMetricsJob: Job? = null
    private var notificationMetricsIntervalMs = 0
    private var lastNotificationContent: NotificationContent? = null
    private val connectionCommandMutex = Mutex()
    private val networkRetargetWakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:network-retarget",
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            alwaysOnVpnState.update(isAlwaysOn)
        }
        createNotificationChannel()
        startAsForeground(
            localizedString(R.string.app_name),
            localizedString(R.string.notification_status_starting),
            showDisconnectAction = false,
        )

        xrayLogStreamer = XrayLogStreamer(filesDir.resolve("xray.log"), logBuffer)
        connectionManager = connectionManagerFactory.create(
            onXrayLogReady = { startLogTail() },
        )
        networkRetargetWorker = NetworkRetargetWorker(
            scope = scope,
            settleDelayMs = NETWORK_RETARGET_SETTLE_DELAY_MS,
            shouldHandle = { activeUseRootService && activeConfig != null },
            beforeBatch = ::acquireNetworkRetargetWakeLock,
            afterBatch = ::releaseNetworkRetargetWakeLock,
            handle = ::retargetNetworkUntilStable,
        )

        scope.launch {
            connectionStateCoordinator.state.drop(1).collect { state ->
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

        scope.launch {
            settingsRepo.xrayMemoryRestartThresholdMiB.collect { thresholdMiB ->
                xrayMemoryRestartThresholdMiB = thresholdMiB
            }
        }

        scope.launch {
            appLocaleChanges.collect {
                refreshLocalizedSystemUi()
            }
        }

        registerNetworkCallback()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLocalizedSystemUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startAsForeground(
                    localizedString(R.string.app_name),
                    localizedString(R.string.notification_status_starting),
                    showDisconnectAction = false,
                )
                val configJson = intent.getStringExtra(EXTRA_SERVER_CONFIG) ?: return START_NOT_STICKY
                launchConnectionCommand {
                    val config = Json.decodeFromString<ServerConfig>(configJson)
                    activeConfig = config
                    stopLogTail()
                    connectWithCurrentSettings(config)
                }
            }
            ACTION_SWITCH_SERVER -> {
                startAsForeground(
                    localizedString(R.string.app_name),
                    localizedString(R.string.notification_status_switching_server),
                    showDisconnectAction = false,
                )
                val configJson = intent.getStringExtra(EXTRA_SERVER_CONFIG) ?: return START_NOT_STICKY
                launchConnectionCommand {
                    val config = Json.decodeFromString<ServerConfig>(configJson)
                    activeConfig = config
                    stopLogTail()
                    stopProcessWatchdog()
                    logBuffer.append(LogSource.APP, "Switching to ${config.name}...")
                    updateNotification(localizedString(R.string.notification_status_switching_server))
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
                if (isRunningAlwaysOnVpn()) {
                    lastNotificationContent = null
                    updateNotification()
                } else {
                    launchDisconnectCommand()
                }
            }
            ACTION_FORCE_DISCONNECT -> launchDisconnectCommand()
            ACTION_RELOAD -> {
                launchConnectionCommand { reloadActiveConnection() }
            }
            ACTION_RELOAD_APP_ROUTING -> {
                launchConnectionCommand { reloadAppRouting() }
            }
            ACTION_RESTORE_STATUS -> {
                launchConnectionCommand {
                    if (isRunningAlwaysOnVpn()) {
                        connectAlwaysOnVpn()
                    } else {
                        restoreRunningConnectionStatus()
                    }
                }
            }
            SERVICE_INTERFACE -> {
                alwaysOnVpnState.update(true)
                launchConnectionCommand { connectAlwaysOnVpn() }
            }
        }
        if (intent == null) {
            launchConnectionCommand {
                if (isRunningAlwaysOnVpn()) {
                    connectAlwaysOnVpn()
                } else {
                    restoreRunningConnectionStatus()
                }
            }
        }
        return START_STICKY
    }

    private fun launchDisconnectCommand() {
        alwaysOnRetryJob?.cancel()
        alwaysOnRetryJob = null
        launchConnectionCommand {
            activeConfig = null
            activePhysicalNetwork = null
            stopLogTail()
            stopProcessWatchdog()
            connectionManager.disconnect()
            closeVpnInterface()
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        activeConfig = null
        activePhysicalNetwork = null
        if (::networkRetargetWorker.isInitialized) networkRetargetWorker.close()
        releaseNetworkRetargetWakeLock()
        processRecoveryJob?.cancel()
        stopBalancerSelectionTracker()
        stopProcessWatchdog()
        stopLogTail()
        if (::connectionManager.isInitialized) connectionManager.prepareForServiceDestruction()
        scope.cancel()
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
                connectionStateCoordinator.startConnection(transitionState)
                updateNotification(
                    localizedString(
                        R.string.notification_status_retrying_connection,
                        attempt,
                        CONNECTION_MAX_ATTEMPTS,
                    ),
                )
            }

            val connected = connectOnceWithCurrentSettings(
                config = config,
                transitionState = transitionState,
                cleanStateFirst = cleanStateFirst || attempt > 1,
                fastReconnect = fastReconnect && attempt == 1,
            )
            if (connected) {
                alwaysOnRetryJob?.cancel()
                alwaysOnRetryJob = null
                return
            }

            val errorState = connectionStateCoordinator.state.value as? ConnectionState.Error
            lastError = errorState?.message ?: localizedString(R.string.notification_unknown_connection_error)
            if (errorState?.retryable == false || attempt == CONNECTION_MAX_ATTEMPTS) break

            delay(CONNECTION_RETRY_DELAY_MS)
            attempt++
        }

        closeVpnInterface()
        val failureMessage = lastError ?: localizedString(R.string.notification_unknown_connection_error)
        val retryable = (connectionStateCoordinator.state.value as? ConnectionState.Error)?.retryable != false
        if (isRunningAlwaysOnVpn() && retryable) {
            scheduleAlwaysOnRetry()
        } else {
            showConnectionFailureNotification(failureMessage)
        }
    }

    private suspend fun connectOnceWithCurrentSettings(
        config: ServerConfig,
        transitionState: ConnectionState = ConnectionState.Connecting,
        cleanStateFirst: Boolean = true,
        fastReconnect: Boolean = false,
    ): Boolean {
        providerRoutingCoordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        val forceVpnService = isRunningAlwaysOnVpn()
        val rootServiceAvailable = if (runtimeSettings.useRootService && !forceVpnService) {
            withContext(Dispatchers.IO) { rootShell.open() }
        } else {
            false
        }
        if (runtimeSettings.useRootService && !forceVpnService && !rootServiceAvailable) {
            settingsRepo.setUseRootService(false)
            connectionStateCoordinator.emitEvent(ConnectionEvent.RootUnavailableFallback)
        }
        val effectiveRuntimeSettings = if (
            shouldUseRootService(runtimeSettings.useRootService, rootServiceAvailable, forceVpnService)
        ) {
            runtimeSettings
        } else {
            runtimeSettings.copy(useRootService = false, tunName = ROOTLESS_TUN_NAME)
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
        if (connectionStateCoordinator.state.value is ConnectionState.Connected && effectiveRuntimeSettings.useRootService) {
            activePhysicalNetwork = currentPhysicalNetworkSnapshot()
        } else if (!effectiveRuntimeSettings.useRootService) {
            activePhysicalNetwork = null
        }
        return connectionStateCoordinator.state.value is ConnectionState.Connected
    }

    private suspend fun connectAlwaysOnVpn() {
        val state = connectionStateCoordinator.state.value
        if (state is ConnectionState.Connected && !activeUseRootService && activeConfig != null) return

        stopLogTail()
        stopProcessWatchdog()
        closeVpnInterface()

        val persistedState = stateFile.read()
        val rootModeConfigured = settingsRepo.useRootService.first()
        when {
            state is ConnectionState.Connected -> {
                connectionManager.disconnect(updateState = false, fastRootCleanup = true)
            }
            rootModeConfigured ||
                persistedState != null &&
                persistedState.physicalInterface != VPN_SERVICE_INTERFACE_LABEL -> {
                connectionManager.ensureCleanRootRuntime()
            }
            else -> stateFile.delete()
        }

        val config = loadLastServerConfig()
        if (config == null) {
            alwaysOnRetryJob?.cancel()
            alwaysOnRetryJob = null
            activeConfig = null
            val message = localizedString(R.string.connection_error_no_server_selected)
            logBuffer.append(LogSource.APP, "Always-on VPN could not start: no server is selected")
            connectionStateCoordinator.markError(message, retryable = false)
            updateNotification()
            showConnectionFailureNotification(message)
            return
        }

        activeConfig = config
        connectWithCurrentSettings(config)
    }

    private fun scheduleAlwaysOnRetry() {
        if (alwaysOnRetryJob?.isActive == true) return
        alwaysOnRetryJob = scope.launch {
            delay(ALWAYS_ON_RETRY_DELAY_MS)
            connectionCommandMutex.withLock {
                alwaysOnRetryJob = null
                if (!isRunningAlwaysOnVpn()) return@withLock
                if (connectionStateCoordinator.state.value is ConnectionState.Connected) return@withLock
                logBuffer.append(LogSource.APP, "Retrying always-on VPN connection...")
                val config = loadLastServerConfig()
                if (config == null) {
                    connectAlwaysOnVpn()
                } else {
                    activeConfig = config
                    connectWithCurrentSettings(config)
                }
            }
        }
    }

    private fun isRunningAlwaysOnVpn(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isAlwaysOn
    } else {
        alwaysOnVpnState.active.value
    }

    private suspend fun reloadActiveConnection() {
        val config = activeConfig ?: return
        stopLogTail()
        stopProcessWatchdog()
        logBuffer.append(LogSource.APP, "Applying routing changes...")
        connectionStateCoordinator.markApplyingRoutingChanges()
        updateNotification()
        connectionManager.disconnect(updateState = false, fastRootCleanup = true)
        closeVpnInterface()
        connectWithCurrentSettings(config, ConnectionState.ApplyingRoutingChanges, cleanStateFirst = false)
    }

    private suspend fun reloadAppRouting() {
        val config = activeConfig ?: return
        val connectedState = connectionStateCoordinator.state.value as? ConnectionState.Connected
        if (connectedState == null) {
            reloadActiveConnection()
            return
        }
        if (!activeUseRootService) {
            reloadActiveConnection()
            return
        }

        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()

        logBuffer.append(LogSource.APP, "Applying app routing changes...")
        connectionStateCoordinator.markApplyingRoutingChanges()
        updateNotification()

        val fastApplied = connectionManager.applyAppRoutingChanges(
            connectedState = connectedState,
            runtimeSettings = runtimeSettings,
        )
        if (fastApplied) {
            connectionStateCoordinator.markConnected(connectedState)
            return
        }

        stopLogTail()
        stopProcessWatchdog()
        logBuffer.append(LogSource.APP, "Restarting Xray to apply app routing topology changes...")
        connectionManager.disconnect(updateState = false, fastRootCleanup = true)
        connectWithCurrentSettings(config, ConnectionState.ApplyingRoutingChanges, cleanStateFirst = false)
    }

    private suspend fun restoreRunningConnectionStatus() {
        val alreadyConnected = connectionStateCoordinator.state.value as? ConnectionState.Connected
        if (alreadyConnected != null && activeConfig != null) {
            activePhysicalNetwork = currentPhysicalNetworkSnapshot()
            handleStateSideEffects(alreadyConnected)
            updateNotification()
            scheduleNetworkRetarget("running status restored", settle = false)
            return
        }

        val restoredState = detectRestorableRunningConnection()
        if (restoredState == null) {
            logBuffer.append(LogSource.APP, "No restorable running Xray state was found")
            if (connectionStateCoordinator.state.value !is ConnectionState.Connected) {
                connectionStateCoordinator.markDisconnected()
                updateNotification()
                stopSelf()
            }
            return
        }

        activeConfig = loadLastServerConfig()
        if (activeConfig == null) {
            logBuffer.append(LogSource.APP, "Restored running status without selected server config")
        }
        if (!connectionManager.restoreRootApiClients()) {
            val config = activeConfig
            if (config != null) {
                logBuffer.append(LogSource.APP, "Restarting Xray to migrate its control API")
                connectionManager.disconnect(updateState = false, fastRootCleanup = true)
                connectWithCurrentSettings(config, cleanStateFirst = false)
                return
            }
            logBuffer.append(LogSource.APP, "Could not secure the restored Xray API; stopping Xray")
            connectionManager.disconnect(updateState = false, fastRootCleanup = true)
            connectionStateCoordinator.markDisconnected()
            updateNotification()
            stopSelf()
            return
        }
        connectionStateCoordinator.restoreConnected(restoredState)
        activePhysicalNetwork = currentPhysicalNetworkSnapshot()
        handleStateSideEffects(restoredState)
        updateNotification()
        if (activeConfig != null) {
            scheduleNetworkRetarget("service state restored", settle = false)
        }
    }

    private suspend fun detectRestorableRunningConnection(): ConnectionState.Connected? = withContext(Dispatchers.IO) {
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        if (!runtimeSettings.useRootService) return@withContext null

        val state = stateFile.read() ?: return@withContext null
        if (state.xrayPid <= 0) return@withContext null
        if (!connectionManager.isProcessAlive(state.xrayPid)) return@withContext null
        if (!TunInterfaceDetector.isInterfaceUp(state.tunName)) return@withContext null

        val persistedRoute = selectRestoredPhysicalRoute(state, fallback = null)
        val fallbackRoute = if (persistedRoute == null) {
            connectionManager.detectPhysicalRoute(state.tunName)
        } else {
            null
        }
        val restoredRoute = persistedRoute ?: fallbackRoute
        ConnectionState.Connected(
            serverName = state.serverName.takeIf { it.isNotBlank() }
                ?: localizedString(R.string.notification_selected_server),
            corePid = state.xrayPid,
            tunName = state.tunName,
            physicalInterface = restoredRoute?.dev ?: "unknown",
            physicalGateway = restoredRoute?.gateway,
            physicalTable = restoredRoute?.table,
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
            is ConnectionState.Connected -> {
                startProcessWatchdog(state)
                startBalancerSelectionTracker()
            }
            else -> {
                stopBalancerSelectionTracker()
                stopProcessWatchdog()
                stopNotificationMetrics()
            }
        }
        updateNotificationMetricsJob()
    }

    private fun startBalancerSelectionTracker() {
        stopBalancerSelectionTracker()
        val balancerTag = activeConfig?.primaryBalancerTag() ?: return

        balancerSelectionJob = scope.launch(Dispatchers.IO) {
            while (isActive && connectionStateCoordinator.state.value is ConnectionState.Connected) {
                val selection = connectionManager.readBalancerSelection(balancerTag)
                connectionStateCoordinator.updateActiveBalancerSelection(selection)
                delay(BALANCER_SELECTION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopBalancerSelectionTracker() {
        balancerSelectionJob?.cancel()
        balancerSelectionJob = null
        connectionStateCoordinator.updateActiveBalancerSelection(null)
    }

    private fun startProcessWatchdog(state: ConnectionState.Connected) {
        if (processWatchdogPid == state.corePid && processWatchdogJob?.isActive == true) return

        stopProcessWatchdog()
        processWatchdogPid = state.corePid
        lastNetworkSafetyCheckAtMs = SystemClock.elapsedRealtime()
        processWatchdogJob = scope.launch(Dispatchers.IO) {
            var keepWatching = true
            while (isActive && keepWatching) {
                delay(PROCESS_WATCHDOG_INTERVAL_MS)
                keepWatching = checkXrayProcessHealth()
            }
        }
    }

    private suspend fun checkXrayProcessHealth(): Boolean {
        val currentState = connectionStateCoordinator.state.value as? ConnectionState.Connected ?: return false
        if (synchronizeAlwaysOnVpnMode()) return false
        val pid = currentState.corePid
        if (!connectionManager.isProcessAlive(pid)) {
            recoverNativeProcess(
                reason = "xray process $pid exited unexpectedly; reconnecting...",
            )
            return false
        }

        val residentMemoryMb = connectionManager.readProcessResidentMemoryMb(pid)
        val thresholdMiB = xrayMemoryRestartThresholdMiB
        if (XrayRuntimeSettings.shouldRestartForMemory(residentMemoryMb, thresholdMiB)) {
            recoverNativeProcess(
                reason = "xray process $pid exceeded $thresholdMiB MiB RSS ($residentMemoryMb MiB); restarting...",
                pidToKill = pid,
            )
            return false
        }
        maybeScheduleNetworkSafetyCheck()
        return true
    }

    private suspend fun synchronizeAlwaysOnVpnMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val alwaysOnVpn = isAlwaysOn
        if (alwaysOnVpnState.active.value != alwaysOnVpn) {
            alwaysOnVpnState.update(alwaysOnVpn)
            withContext(Dispatchers.Main) {
                lastNotificationContent = null
                updateNotification()
            }
        }
        val rootServiceRequested = settingsRepo.useRootService.first()
        val shouldRunRootService = rootServiceRequested && !alwaysOnVpn
        if (activeUseRootService == shouldRunRootService) return false

        recoverNativeProcess(
            reason = if (alwaysOnVpn) {
                "Always-on VPN enabled; switching to Android VpnService..."
            } else {
                "Always-on VPN disabled; restoring the configured service mode..."
            },
        )
        return true
    }

    private suspend fun maybeScheduleNetworkSafetyCheck() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkSafetyCheckAtMs < NETWORK_SAFETY_CHECK_INTERVAL_MS) return
        lastNetworkSafetyCheckAtMs = now

        withContext(Dispatchers.Main.immediate) {
            if (!activeUseRootService || activeConfig == null) return@withContext

            val currentNetwork = currentPhysicalNetworkSnapshot() ?: return@withContext
            val networkChanged = activePhysicalNetwork?.sameNetwork(currentNetwork) != true
            if (networkChanged || !networkCallbacksAvailable) {
                scheduleNetworkRetarget(
                    reason = if (networkChanged) "watchdog observed a new network" else "network callback fallback",
                    settle = false,
                )
            }
        }
    }

    private fun stopProcessWatchdog() {
        processWatchdogJob?.cancel()
        processWatchdogJob = null
        processWatchdogPid = null
    }

    private fun updateNotificationMetricsJob() {
        val state = connectionStateCoordinator.state.value
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
                val connectedState = connectionStateCoordinator.state.value as? ConnectionState.Connected ?: break
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

                stopLogTail()
                stopProcessWatchdog()
                logBuffer.append(LogSource.APP, reason)

                if (pidToKill != null) {
                    connectionManager.killProcess(pidToKill, signal = 9)
                }

                connectionStateCoordinator.startConnection(ConnectionState.Connecting)
                updateNotification(localizedString(R.string.notification_status_recovering_core))
                connectionManager.disconnect(updateState = false, fastRootCleanup = true)
                delay(PROCESS_RESTART_DELAY_MS)
                connectWithCurrentSettings(config, cleanStateFirst = false)
            }
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callbackHandler = Handler(mainLooper)
        val defaultRegistered = registerNetworkWatcher("default") { callback ->
            connectivityManager.registerDefaultNetworkCallback(callback, callbackHandler)
        }

        val physicalNetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val physicalRegistered = registerNetworkWatcher("physical") { callback ->
            connectivityManager.registerNetworkCallback(physicalNetworkRequest, callback, callbackHandler)
        }
        networkCallbacksAvailable = defaultRegistered || physicalRegistered
    }

    private fun registerNetworkWatcher(
        source: String,
        register: (ConnectivityManager.NetworkCallback) -> Unit,
    ): Boolean {
        val callback = networkCallback(source)
        return runCatching {
            register(callback)
            networkCallbacks += callback
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                logBuffer.append(LogSource.APP, "Could not watch $source network changes: ${error.message}")
                false
            },
        )
    }

    private fun acquireNetworkRetargetWakeLock() {
        runCatching {
            networkRetargetWakeLock.acquire(NETWORK_RETARGET_WAKE_LOCK_TIMEOUT_MS)
        }.onFailure { error ->
            logBuffer.append(LogSource.APP, "Could not hold CPU awake for network change: ${error.message}")
        }
    }

    private fun releaseNetworkRetargetWakeLock() {
        runCatching {
            if (networkRetargetWakeLock.isHeld) networkRetargetWakeLock.release()
        }
    }

    private fun networkCallback(source: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleNetworkRetarget("$source available", settle = false)
        }

        override fun onLost(network: Network) {
            scheduleNetworkRetarget("$source lost", settle = false)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleNetworkRetarget("$source capabilities changed", settle = true)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            scheduleNetworkRetarget("$source link properties changed", settle = true)
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
        networkCallbacksAvailable = false
    }

    private fun scheduleNetworkRetarget(reason: String, settle: Boolean) {
        if (!activeUseRootService) return
        activeConfig ?: return
        networkRetargetWorker.signal(reason, settle)
    }

    private suspend fun retargetNetworkUntilStable(reason: String) {
        val outcome = retryNetworkRetarget(
            retryDelaysMs = NETWORK_RETARGET_RETRY_DELAYS_MS,
            shouldContinue = { activeUseRootService && activeConfig != null },
        ) { attempt ->
            retargetNetwork(reason, attempt)
        }
        if (
            outcome == NetworkRetargetRetryOutcome.Exhausted &&
            activeUseRootService &&
            activeConfig != null
        ) {
            logBuffer.append(LogSource.APP, "Network changed ($reason), but no usable physical route appeared")
            updateNotification(localizedString(R.string.notification_status_waiting_for_physical_route))
        }
    }

    private suspend fun retargetNetwork(reason: String, attempt: Int): NetworkRetargetResult = connectionCommandMutex.withLock {
        if (!activeUseRootService) return@withLock NetworkRetargetResult.Done
        val latestConfig = activeConfig ?: return@withLock NetworkRetargetResult.Done
        val latestState = connectionStateCoordinator.state.value as? ConnectionState.Connected
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
            updateNotification(localizedString(R.string.notification_status_waiting_for_physical_route))
            return@withLock NetworkRetargetResult.Retry
        }

        val androidNetworkChanged = previousNetwork != null &&
            currentNetwork != null &&
            !previousNetwork.sameNetwork(currentNetwork)
        val physicalRouteChanged = !currentRoute.matches(latestState)
        if (shouldWaitForMatchingPhysicalNetwork(reason, attempt, previousNetwork, currentNetwork, currentRoute)) {
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
        updateNotification(localizedString(R.string.notification_status_refreshing_physical_route))
        when (
            val result = withContext(Dispatchers.IO) {
                connectionManager.reapplyPhysicalRoutingForNetworkChange(
                    connectedState = latestState,
                    runtimeSettings = settingsRepo.runtimeSettingsSnapshot(),
                )
            }
        ) {
            is PhysicalRouteUpdateResult.Applied -> {
                connectionStateCoordinator.markConnected(
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
                updateNotification(localizedString(R.string.notification_status_waiting_for_physical_route))
                NetworkRetargetResult.Retry
            }
            PhysicalRouteUpdateResult.RequiresReconnect -> {
                reconnectForPhysicalRouteChange(latestConfig, latestState, currentRoute)
                NetworkRetargetResult.Done
            }
        }
    }

    private fun shouldWaitForMatchingPhysicalNetwork(
        reason: String,
        attempt: Int,
        previousNetwork: PhysicalNetworkSnapshot?,
        currentNetwork: PhysicalNetworkSnapshot?,
        currentRoute: TunManager.PhysicalRoute,
    ): Boolean {
        if (previousNetwork != null && currentNetwork == null) {
            if (attempt == 1) {
                logBuffer.append(LogSource.APP, "Network changed ($reason), waiting for an active physical network")
            }
            updateNotification(localizedString(R.string.notification_status_waiting_for_physical_network))
            return true
        }
        if (currentNetwork?.interfaceName != null && currentRoute.dev != currentNetwork.interfaceName) {
            if (attempt == 1) {
                logBuffer.append(
                    LogSource.APP,
                    "Network changed ($reason), waiting for root route ${currentRoute.dev} to match " +
                        "Android interface ${currentNetwork.interfaceName}",
                )
            }
            updateNotification(localizedString(R.string.notification_status_waiting_for_physical_route))
            return true
        }
        return false
    }

    private suspend fun reconnectForPhysicalRouteChange(
        config: ServerConfig,
        previousState: ConnectionState.Connected,
        currentRoute: TunManager.PhysicalRoute,
    ) {
        updateNotification(
            localizedString(
                R.string.notification_status_pinning_interface,
                previousState.tunName,
                currentRoute.dev,
            ),
        )
        stopLogTail()
        stopProcessWatchdog()
        connectionStateCoordinator.startConnection(ConnectionState.Connecting)
        connectionManager.disconnect(updateState = false, fastRootCleanup = true)
        closeVpnInterface()
        connectWithCurrentSettings(config, cleanStateFirst = false)
    }

    private suspend fun setupVpnInterface(runtimeSettings: XrayRuntimeSettings): ParcelFileDescriptor? {
        if (prepare(this) != null) {
            connectionStateCoordinator.markError(
                message = localizedString(R.string.connection_error_vpn_permission_required),
                retryable = false,
            )
            stopSelf()
            return null
        }

        val builder = Builder()
            .setSession(localizedString(R.string.app_name))
            .setMtu(runtimeSettings.tunMtu)
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
        val establishError = localizedString(R.string.connection_error_vpn_interface)
        return runCatching { builder.establish() ?: error(establishError) }
            .onSuccess { descriptor ->
                vpnInterface = descriptor
                logBuffer.append(LogSource.APP, "Android VPN interface established")
            }
            .onFailure { error ->
                connectionStateCoordinator.markError(error.message ?: establishError)
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
        alwaysOnVpnState.update(false)
        alwaysOnRetryJob?.cancel()
        alwaysOnRetryJob = null
        launchConnectionCommand {
            activeConfig = null
            activePhysicalNetwork = null
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
            interfaceName = connectivityManager.getLinkProperties(this)?.interfaceName,
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
        val state = connectionStateCoordinator.state.value
        if (state is ConnectionState.Disconnected) {
            lastNotificationContent = null
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val title = when (state) {
            is ConnectionState.Connected -> state.serverName
            else -> localizedString(R.string.app_name)
        }
        val text = overrideText ?: when (state) {
            is ConnectionState.Connected -> connectedNotificationText(state)
            is ConnectionState.Connecting -> localizedString(R.string.notification_status_connecting)
            ConnectionState.ApplyingRoutingChanges ->
                localizedString(R.string.notification_status_applying_routing_changes)
            ConnectionState.UpdatingRoutingData -> localizedString(R.string.notification_status_updating_routing_data)
            is ConnectionState.RestartRequired -> localizedString(R.string.notification_status_restart_required)
            is ConnectionState.InterfaceBusy -> localizedString(R.string.notification_status_interface_busy)
            is ConnectionState.Disconnecting -> localizedString(R.string.notification_status_disconnecting)
            is ConnectionState.Error -> localizedString(R.string.notification_status_error, state.message)
            ConnectionState.Disconnected -> return
        }
        val showDisconnectAction = state !is ConnectionState.Error && !alwaysOnVpnState.active.value
        val content = NotificationContent(title, text, showDisconnectAction)
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text, showDisconnectAction))
    }

    private fun refreshLocalizedSystemUi() {
        lastNotificationContent = null
        createNotificationChannel()
        updateNotification()
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
            .setContentTitle(localizedString(R.string.notification_connection_failed))
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
            localizedString(R.string.notification_vpn_service_active)
        } else {
            localizedString(
                R.string.notification_root_service_active,
                state.tunName,
                state.physicalInterface,
            )
        }
        val settings = notificationSettings
        if (!settings.enabled || !settings.anyFieldEnabled) return baseText

        val metrics = notificationMetrics
        val separator = if (settings.style == NotificationStyle.Compact) {
            localizedString(R.string.notification_separator_compact)
        } else {
            localizedString(R.string.notification_separator_expanded)
        }
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
                if (compact) {
                    localizedString(R.string.notification_traffic_compact, proxy, direct)
                } else {
                    localizedString(R.string.notification_traffic_expanded, proxy, direct)
                }
            }
            NotificationField.RamUsage -> {
                val ram = metrics.ramMb?.let(::formatMebibytes)
                    ?: localizedString(R.string.notification_metric_unavailable)
                if (compact) {
                    localizedString(R.string.notification_ram_compact, ram)
                } else {
                    localizedString(R.string.notification_ram_expanded, ram)
                }
            }
            NotificationField.ConnectionCount -> {
                val count = metrics.connectionCount
                val formattedCount = count?.let { formatInteger(it.toLong()) }
                    ?: localizedString(R.string.notification_metric_unavailable)
                when {
                    compact -> localizedString(R.string.notification_connections_compact, formattedCount)
                    count == null -> localizedString(
                        R.string.notification_connections_expanded_unavailable,
                        formattedCount,
                    )
                    else -> forAppLanguage().resources.getQuantityString(
                        R.plurals.notification_connections_expanded,
                        count,
                        formattedCount,
                    )
                }
            }
        }
    }

    private fun Map<String, Long>.outboundBytes(tag: String): Long = get("outbound>>>$tag>>>traffic>>>uplink").orZero() +
        get("outbound>>>$tag>>>traffic>>>downlink").orZero()

    private fun Map<String, Long>.hasOutboundTrafficStats(tag: String): Boolean = containsKey("outbound>>>$tag>>>traffic>>>uplink") ||
        containsKey("outbound>>>$tag>>>traffic>>>downlink")

    private fun Long?.orZero(): Long = this ?: 0L

    private fun formatNullableBytesPerSecond(bytesPerSecond: Long?): String = bytesPerSecond?.let(::formatBytesPerSecond)
        ?: localizedString(R.string.notification_metric_unavailable)

    private fun formatBytesPerSecond(bytesPerSecond: Long): String {
        val units = intArrayOf(
            R.string.notification_unit_bytes_per_second,
            R.string.notification_unit_kibibytes_per_second,
            R.string.notification_unit_mebibytes_per_second,
            R.string.notification_unit_gibibytes_per_second,
        )
        var value = bytesPerSecond.coerceAtLeast(0).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val formattedValue = if (unitIndex == 0) {
            formatInteger(value.toLong())
        } else {
            NumberFormat.getNumberInstance(forAppLanguage().resources.configuration.locales[0]).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }.format(value)
        }
        return localizedString(
            R.string.notification_value_with_unit,
            formattedValue,
            localizedString(units[unitIndex]),
        )
    }

    private fun formatMebibytes(value: Long): String = localizedString(
        R.string.notification_value_with_unit,
        formatInteger(value),
        localizedString(R.string.notification_unit_mebibytes),
    )

    private fun formatInteger(value: Long): String = NumberFormat.getIntegerInstance(forAppLanguage().resources.configuration.locales[0]).format(value)

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
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localizedString(R.string.notification_disconnect),
                disconnectIntent,
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW,
        )
        val failureChannel = NotificationChannel(
            FAILURE_CHANNEL_ID,
            localizedString(R.string.notification_channel_connection_failures),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(failureChannel)
        }
    }

    private data class PhysicalNetworkSnapshot(
        val handle: Long,
        val label: String,
        val interfaceName: String?,
        val validated: Boolean,
        val priority: Int,
    ) {
        fun sameNetwork(other: PhysicalNetworkSnapshot): Boolean = handle == other.handle &&
            interfaceName == other.interfaceName

        fun describe(): String = buildString {
            append("$label#$handle")
            if (!interfaceName.isNullOrBlank()) append(" on $interfaceName")
            append(if (validated) " validated" else " unvalidated")
        }
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

    companion object {
        const val CHANNEL_ID = "xray_service"
        const val FAILURE_CHANNEL_ID = "xray_connection_failures"
        const val NOTIFICATION_ID = 1
        const val FAILURE_NOTIFICATION_ID = 2
        const val ACTION_CONNECT = "com.material.xray.CONNECT"
        const val ACTION_SWITCH_SERVER = "com.material.xray.SWITCH_SERVER"
        const val ACTION_DISCONNECT = "com.material.xray.DISCONNECT"
        private const val ACTION_FORCE_DISCONNECT = "com.material.xray.FORCE_DISCONNECT"
        const val ACTION_RELOAD = "com.material.xray.RELOAD"
        const val ACTION_RELOAD_APP_ROUTING = "com.material.xray.RELOAD_APP_ROUTING"
        const val ACTION_RESTORE_STATUS = "com.material.xray.RESTORE_STATUS"
        const val EXTRA_SERVER_CONFIG = "server_config"
        private const val NETWORK_RETARGET_SETTLE_DELAY_MS = 250L
        private const val NETWORK_RETARGET_WAKE_LOCK_TIMEOUT_MS = 30_000L
        private const val NETWORK_SAFETY_CHECK_INTERVAL_MS = 60_000L
        private val NETWORK_RETARGET_RETRY_DELAYS_MS = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L)
        private const val CONNECTION_MAX_ATTEMPTS = 3
        private const val CONNECTION_RETRY_DELAY_MS = 1_500L
        private const val ALWAYS_ON_RETRY_DELAY_MS = 30_000L
        private const val PROCESS_RESTART_DELAY_MS = 2_000L
        private const val PROCESS_WATCHDOG_INTERVAL_MS = 10_000L
        private const val BALANCER_SELECTION_POLL_INTERVAL_MS = 5_000L
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

        fun disconnect(context: Context, force: Boolean = false) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(
                    if (force) ACTION_FORCE_DISCONNECT else ACTION_DISCONNECT,
                ),
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

internal fun shouldUseRootService(
    requested: Boolean,
    available: Boolean,
    alwaysOnVpn: Boolean,
): Boolean = requested && available && !alwaysOnVpn

internal fun selectRestoredPhysicalRoute(
    state: XrayState,
    fallback: TunManager.PhysicalRoute?,
): TunManager.PhysicalRoute? {
    val persistedInterface = state.physicalInterface?.takeIf { it.isNotBlank() } ?: return fallback
    return TunManager.PhysicalRoute(
        dev = persistedInterface,
        gateway = state.physicalGateway,
        table = state.physicalTable,
    )
}
