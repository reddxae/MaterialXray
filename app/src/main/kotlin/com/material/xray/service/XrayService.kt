package com.material.xray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.material.xray.R
import com.material.xray.core.format.rateUnit
import com.material.xray.core.format.scaleBytes
import com.material.xray.core.format.sizeUnit
import com.material.xray.core.locale.appLocaleChanges
import com.material.xray.core.locale.forAppLanguage
import com.material.xray.core.locale.localizedString
import com.material.xray.core.network.ServerLatencyTester
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.core.xray.TproxyCompatibilityDetector
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XrayStateReadResult
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.repository.ProviderRoutingActiveUpdate
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.ServerConfig
import com.material.xray.model.SessionTrafficMetrics
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.model.primaryBalancerTag
import com.material.xray.model.proxyOutboundCount
import dagger.hilt.android.AndroidEntryPoint
import java.io.FileDescriptor
import java.io.PrintWriter
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    @Inject lateinit var startupDiagnosticsLogger: StartupDiagnosticsLogger

    @Inject lateinit var serverLatencyTester: ServerLatencyTester

    @Inject lateinit var tproxyCompatibilityDetector: TproxyCompatibilityDetector

    @Inject lateinit var activeConfigOverrideStore: ActiveConfigOverrideStore

    private lateinit var connectionManager: ConnectionManager
    private lateinit var connectionLifecycle: ConnectionLifecycle
    private lateinit var healthWatchdog: XrayHealthWatchdog
    private lateinit var xrayLogStreamer: XrayLogStreamer
    private val stepExecutor by lazy {
        ConnectionStepExecutor(
            elapsedRealtime = SystemClock::elapsedRealtime,
            log = { message -> logBuffer.append(LogSource.APP, message) },
            onProgressStarted = connectionStateCoordinator::beginConnectionProgress,
            onProgressFinished = connectionStateCoordinator::endConnectionProgress,
        )
    }
    private val stateFile by lazy { StateFile(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeConfig: ServerConfig?
        get() = connectionLifecycle.activeConfig
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private lateinit var networkRetargetWorker: NetworkRetargetWorker
    private var activePhysicalNetwork: PhysicalNetworkSnapshot? = null
    private var networkCallbacksAvailable = false

    // Written by main-thread command paths, read and rewritten by the IO watchdog loop.
    @Volatile
    private var lastNetworkSafetyCheckAtMs = 0L
    private var processRecoveryJob: Job? = null
    private var alwaysOnRetryJob: Job? = null
    private var fullReconfigurationPending = false

    @Volatile
    private var xrayMemoryRestartThresholdMiB = XrayRuntimeSettings.DEFAULT_XRAY_MEMORY_RESTART_THRESHOLD_MIB

    @Volatile
    private var passiveHealthMonitoringEnabled = SettingsRepository.DEFAULT_PASSIVE_HEALTH_MONITORING_ENABLED

    @Volatile
    private var rootServiceRequested: Boolean? = null
    private var balancerSelectionJob: Job? = null
    private var balancerSelectionTag: String? = null
    private var pingJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    // Written on the main thread, read by the IO metrics loop.
    @Volatile
    private var notificationSettings = NotificationSettings()

    // Written on the main thread, read by the IO metrics loop to skip redundant updates.
    @Volatile
    private var notificationMetrics = NotificationMetrics()
    private var metricsJob: Job? = null
    private var metricsIntervalMs = 0
    private var lastNotificationContent: NotificationContent? = null
    private var terminalFailureNotificationShown = false
    private var screenInteractive = true
    private var screenStateReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val interactive = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> return
            }
            if (screenInteractive == interactive) return
            screenInteractive = interactive
            updateBalancerSelectionTracker()
            updatePingTracker()
            updateMetricsJob()
        }
    }
    private val networkRetargetWakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:network-retarget",
        ).apply {
            setReferenceCounted(false)
        }
    }
    private val connectionCommandWakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:connection-command",
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

        screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        xrayLogStreamer = XrayLogStreamer(filesDir.resolve(XRAY_LOG_FILE_NAME), logBuffer).also { it.start(scope) }
        connectionManager = connectionManagerFactory.create()
        connectionLifecycle = ConnectionLifecycle(
            scope = scope,
            beforeCommand = ::acquireConnectionCommandWakeLock,
            afterCommand = ::releaseConnectionCommandWakeLock,
            runAttempt = { request ->
                executeStep(
                    ConnectionStep(
                        label = "Establish Xray connection",
                        progress = ConnectionProgress.PreparingRuntime,
                        isSuccessful = { it },
                        action = {
                            connectOnceWithCurrentSettings(
                                config = request.config,
                                transitionState = request.transitionState,
                                preparation = request.preparation,
                            )
                        },
                    ),
                )
            },
            currentFailure = {
                val error = connectionStateCoordinator.state.value as? ConnectionState.Error
                ConnectionFailure(
                    message = error?.message ?: localizedString(R.string.notification_unknown_connection_error),
                    retryable = error?.retryable != false,
                )
            },
            onConnected = {
                alwaysOnRetryJob?.cancel()
                alwaysOnRetryJob = null
            },
            onExhausted = { failure ->
                connectionManager.clearFailedTransitionGuard()
                closeVpnInterface()
                if (isRunningAlwaysOnVpn() && failure.retryable) {
                    scheduleAlwaysOnRetry()
                } else {
                    showConnectionFailureNotification(failure.message)
                }
            },
            onCommandFailure = ::handleUnexpectedCommandFailure,
        )
        healthWatchdog = XrayHealthWatchdog(
            scope = scope,
            stateCoordinator = connectionStateCoordinator,
            healthProbe = connectionManager,
            log = logBuffer,
            config = XrayHealthWatchdogConfig(
                processIntervalMs = PROCESS_WATCHDOG_INTERVAL_MS,
                memoryCheckIntervalMs = MEMORY_HEALTH_CHECK_INTERVAL_MS,
                apiProbeIntervalMs = LOCAL_API_HEALTH_PROBE_INTERVAL_MS,
                snapshotIntervalMs = LOCAL_HEALTH_SNAPSHOT_INTERVAL_MS,
                tunnelFailureThreshold = TUNNEL_FAILURE_THRESHOLD,
                apiFailureThreshold = LOCAL_API_FAILURE_THRESHOLD,
                checkFailureLogThreshold = HEALTH_CHECK_FAILURE_LOG_THRESHOLD,
            ),
            passiveMonitoringEnabled = { passiveHealthMonitoringEnabled },
            memoryRestartThresholdMiB = { xrayMemoryRestartThresholdMiB },
            elapsedRealtime = SystemClock::elapsedRealtime,
            tunnelAvailable = { state ->
                if (connectionManager.isUsingRootRuntime) {
                    connectionManager.isRootTrafficAvailable(TunInterfaceDetector.isInterfaceUp(state.tunName))
                } else {
                    vpnInterface?.fileDescriptor?.valid() == true
                }
            },
            runtimeModeRecoveryReason = ::runtimeModeRecoveryReason,
            scheduleNetworkSafetyCheck = ::maybeScheduleNetworkSafetyCheck,
            recover = { reason, watchedPid, pidToKill ->
                recoverNativeProcess(reason, watchedPid, pidToKill)
            },
        )
        networkRetargetWorker = NetworkRetargetWorker(
            scope = scope,
            settleDelayMs = NETWORK_RETARGET_SETTLE_DELAY_MS,
            shouldHandle = { connectionManager.isUsingRootRuntime && activeConfig != null },
            beforeBatch = ::acquireNetworkRetargetWakeLock,
            afterBatch = ::releaseNetworkRetargetWakeLock,
            onFailure = { error ->
                logBuffer.append(LogSource.APP, "Root network verification failed: ${error.message ?: error.javaClass.simpleName}")
            },
            handle = ::retargetNetworkUntilStable,
        )

        scope.launch {
            connectionStateCoordinator.state.drop(1).collect { state ->
                handleStateSideEffects(state)
                updateNotification()
            }
        }

        scope.launch {
            connectionStateCoordinator.activeBalancerSelectionSubscribers.collect {
                updateBalancerSelectionTracker()
            }
        }

        scope.launch {
            connectionStateCoordinator.sessionTrafficSubscribers.collect {
                updateMetricsJob()
            }
        }

        scope.launch {
            connectionStateCoordinator.activePingSubscribers.collect {
                updatePingTracker()
            }
        }

        scope.launch {
            settingsRepo.notificationSettings.collectLatest { settings ->
                notificationSettings = settings
                if (!settings.showTrafficSpeed) {
                    notificationMetrics = notificationMetrics.copy(proxyBps = null, directBps = null)
                }
                if (!settings.showSessionTraffic) {
                    notificationMetrics = notificationMetrics.copy(sessionUplinkBytes = null, sessionDownlinkBytes = null)
                }
                if (!settings.showPing) notificationMetrics = notificationMetrics.copy(pingMs = null)
                updateMetricsJob()
                updatePingTracker()
                updateNotification()
            }
        }

        scope.launch {
            settingsRepo.xrayMemoryRestartThresholdMiB.collect { thresholdMiB ->
                xrayMemoryRestartThresholdMiB = thresholdMiB
            }
        }

        scope.launch {
            settingsRepo.useRootService.collect { requested ->
                rootServiceRequested = requested
            }
        }

        scope.launch {
            settingsRepo.passiveHealthMonitoringEnabled.collect { enabled ->
                if (passiveHealthMonitoringEnabled == enabled) return@collect
                passiveHealthMonitoringEnabled = enabled
                logBuffer.append(
                    LogSource.APP,
                    "Passive connection health monitoring ${if (enabled) "enabled" else "disabled"}",
                )
                if (connectionStateCoordinator.state.value !is ConnectionState.Connected) return@collect
                stopProcessWatchdog()
                (connectionStateCoordinator.state.value as? ConnectionState.Connected)?.let(::startProcessWatchdog)
            }
        }

        scope.launch {
            appLocaleChanges.collect {
                refreshLocalizedSystemUi()
            }
        }

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenStateReceiverRegistered = true
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
                    connectionLifecycle.updateActiveConfig(config)
                    connectWithCurrentSettings(config)
                }
            }
            ACTION_AUTO_CONNECT -> {
                startAsForeground(
                    localizedString(R.string.app_name),
                    localizedString(R.string.notification_status_starting),
                    showDisconnectAction = false,
                )
                launchConnectionCommand { autoConnect() }
            }
            ACTION_RECOVER_AFTER_PACKAGE_REPLACEMENT -> {
                startAsForeground(
                    localizedString(R.string.app_name),
                    localizedString(R.string.notification_status_starting),
                    showDisconnectAction = false,
                )
                launchConnectionCommand {
                    if (isRunningAlwaysOnVpn()) {
                        connectAlwaysOnVpn()
                    } else {
                        restoreRunningConnectionStatus(connectIfMissing = true)
                    }
                }
            }
            ACTION_SWITCH_SERVER -> {
                startAsForeground(
                    localizedString(R.string.app_name),
                    localizedString(R.string.notification_status_switching_server),
                    showDisconnectAction = false,
                )
                requestReconfiguration(forceFull = false)
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
                requestReconfiguration(forceFull = true)
            }
            ACTION_RELOAD_APP_ROUTING -> {
                launchConnectionCommand { reloadAppRouting() }
            }
            ACTION_RESTORE_STATUS -> {
                startAsForeground(
                    localizedString(R.string.app_name),
                    localizedString(R.string.notification_status_starting),
                    showDisconnectAction = false,
                )
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
            connectionLifecycle.updateActiveConfig(null)
            activePhysicalNetwork = null
            stopProcessWatchdog()
            connectionManager.disconnect()
            closeVpnInterface()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (screenStateReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenStateReceiverRegistered = false
        }
        unregisterNetworkCallback()
        if (::connectionLifecycle.isInitialized) connectionLifecycle.updateActiveConfig(null)
        activePhysicalNetwork = null
        if (::networkRetargetWorker.isInitialized) networkRetargetWorker.close()
        releaseNetworkRetargetWakeLock()
        releaseConnectionCommandWakeLock()
        processRecoveryJob?.cancel()
        stopBalancerSelectionTracker()
        stopProcessWatchdog()
        if (::xrayLogStreamer.isInitialized) xrayLogStreamer.close()
        val rootManagedTunnel = ::connectionManager.isInitialized &&
            (connectionManager.isUsingRootRuntime || connectionStateCoordinator.state.value.describesRootManagedTunnel())
        if (::connectionManager.isInitialized) connectionManager.prepareForServiceDestruction()
        scope.cancel()
        // A root-managed core keeps running after this service is gone, so a state describing it
        // stays true. The rootless core is a child of this process and dies with it, so leaving
        // that state as connected would strand the UI, the tile, and every disconnect request.
        if (!rootManagedTunnel) connectionStateCoordinator.markRuntimeStopped()
        closeVpnInterface()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("state=${connectionStateCoordinator.state.value}")
        writer.println("rootRuntime=${::connectionManager.isInitialized && connectionManager.isUsingRootRuntime}")
        writer.println("--- logs ---")
        writer.print(logBuffer.formatAll())
    }

    private fun launchConnectionCommand(block: suspend () -> Unit) {
        connectionLifecycle.launch(block)
    }

    private fun requestReconfiguration(forceFull: Boolean) {
        fullReconfigurationPending = fullReconfigurationPending || forceFull
        connectionLifecycle.launchLatest(RECONFIGURE_SETTLE_DELAY_MS) {
            val runFullReconfiguration = fullReconfigurationPending
            fullReconfigurationPending = false
            reloadActiveConnection(forceFull = runFullReconfiguration)
        }
    }

    private suspend fun <T> runConnectionCommand(block: suspend () -> T): T = connectionLifecycle.serialized(block)

    private suspend fun <T> executeStep(step: ConnectionStep<T>): T = stepExecutor.execute(step)

    private suspend fun connectWithCurrentSettings(config: ServerConfig): Boolean = connectWithCurrentSettings(
        config,
        ConnectionState.Connecting,
    )

    private suspend fun connectWithCurrentSettings(
        config: ServerConfig,
        transitionState: ConnectionState = ConnectionState.Connecting,
        preparation: ConnectionPreparation = ConnectionPreparation.Full,
    ): Boolean {
        startupDiagnosticsLogger.logIfMissing()
        terminalFailureNotificationShown = false
        getSystemService(NotificationManager::class.java).cancel(FAILURE_NOTIFICATION_ID)
        return connectionLifecycle.connect(
            ConnectionRequest(
                config = config,
                transitionState = transitionState,
                preparation = preparation,
            ),
        )
    }

    /**
     * Stops the running core and connects [config] again without publishing a disconnected state,
     * so the change reads as one continuous transition rather than a drop followed by a connect.
     *
     * Returns false when the runtime could not be stopped, which leaves the previous connection
     * state untouched and means the caller must not treat the restart as having happened.
     */
    private suspend fun restartRuntime(
        config: ServerConfig,
        transitionState: ConnectionState = ConnectionState.Connecting,
        preparation: ConnectionPreparation = ConnectionPreparation.ReusePreparedRuntime,
        reconnectDelayMs: Long = 0,
    ): Boolean = executeStep(
        ConnectionStep(
            label = "Restart Xray runtime",
            progress = ConnectionProgress.StoppingCore,
            isSuccessful = { it },
            action = { restartRuntimeOnce(config, transitionState, preparation, reconnectDelayMs) },
        ),
    )

    private suspend fun restartRuntimeOnce(
        config: ServerConfig,
        transitionState: ConnectionState,
        preparation: ConnectionPreparation,
        reconnectDelayMs: Long,
    ): Boolean {
        if (!connectionManager.prepareSeamlessReconnect()) return false
        if (
            !connectionManager.disconnect(
                updateState = false,
                fastCleanup = true,
                preserveTproxyGuard = connectionManager.hasTransitionGuard,
            )
        ) {
            return false
        }
        // A no-op in root mode, where the tunnel belongs to the system rather than to this
        // process. Rootless reconnects establish a fresh descriptor either way.
        closeVpnInterface()
        if (reconnectDelayMs > 0) delay(reconnectDelayMs)
        return connectWithCurrentSettings(config, transitionState, preparation)
    }

    private suspend fun connectOnceWithCurrentSettings(
        config: ServerConfig,
        transitionState: ConnectionState = ConnectionState.Connecting,
        preparation: ConnectionPreparation = ConnectionPreparation.Full,
    ): Boolean {
        executeStep(
            ConnectionStep("Refresh selected server routing", ConnectionProgress.PreparingRuntime) {
                providerRoutingCoordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)
            },
        )
        val runtimeSettings = executeStep(
            ConnectionStep("Load runtime settings", ConnectionProgress.PreparingRuntime) {
                settingsRepo.runtimeSettingsSnapshot()
            },
        )
        rootServiceRequested = runtimeSettings.useRootService
        val forceVpnService = isRunningAlwaysOnVpn()
        val rootServiceAvailable = if (runtimeSettings.useRootService && !forceVpnService) {
            executeStep(
                ConnectionStep(
                    "Check root runtime access",
                    ConnectionProgress.PreparingRuntime,
                    isSuccessful = { it },
                    action = {
                        withContext(Dispatchers.IO) { rootShell.open(RootShell.NetworkNamespace.INIT) }
                    },
                ),
            )
        } else {
            false
        }
        if (runtimeSettings.useRootService && !forceVpnService && !rootServiceAvailable) {
            if (runtimeSettings.rootConnectionBackend == RootConnectionBackend.Tproxy) {
                val message = localizedString(
                    R.string.connection_error_tproxy_unsupported,
                    "root access or the init network namespace is unavailable",
                )
                logBuffer.append(LogSource.APP, message)
                connectionStateCoordinator.markError(message)
                return false
            }
            settingsRepo.setUseRootService(false)
            rootServiceRequested = false
            logBuffer.append(
                LogSource.APP,
                "Root mode cannot access Android's init network namespace; using Android VpnService",
            )
            connectionStateCoordinator.emitEvent(ConnectionEvent.RootUnavailableFallback)
        }
        val useRootService = shouldUseRootService(runtimeSettings.useRootService, rootServiceAvailable, forceVpnService)
        val baseRuntimeSettings = if (useRootService) {
            runtimeSettings
        } else {
            runtimeSettings.copy(useRootService = false, tunName = ROOTLESS_TUN_NAME)
        }
        val tproxyCompatibility = if (
            useRootService && runtimeSettings.rootConnectionBackend == RootConnectionBackend.Tproxy
        ) {
            tproxyCompatibilityDetector.detect()
        } else {
            TproxyCompatibility.Unknown
        }
        val effectiveRuntimeSettings = baseRuntimeSettings.copy(
            allowIpv6 = effectiveTproxyIpv6(
                requested = baseRuntimeSettings.allowIpv6,
                useRootService = baseRuntimeSettings.useRootService,
                backend = baseRuntimeSettings.rootConnectionBackend,
                compatibility = tproxyCompatibility,
            ),
        )
        if (baseRuntimeSettings.allowIpv6 && !effectiveRuntimeSettings.allowIpv6) {
            logBuffer.append(LogSource.APP, "TPROXY IPv6 is unavailable; using IPv4-only TPROXY")
        }
        val rootlessNetworkPlan = if (effectiveRuntimeSettings.useRootService) {
            null
        } else {
            planRootlessVpnNetwork(effectiveRuntimeSettings.allowIpv6)
        }
        val activeVpnInterface = if (rootlessNetworkPlan == null) {
            closeVpnInterface()
            null
        } else {
            executeStep(
                ConnectionStep(
                    "Establish Android VPN interface",
                    ConnectionProgress.ConfiguringTunnel,
                    isSuccessful = { it != null },
                    action = { setupVpnInterface(effectiveRuntimeSettings, rootlessNetworkPlan) },
                ),
            ) ?: return false
        }
        connectionManager.connect(
            server = config,
            runtimeSettings = effectiveRuntimeSettings,
            vpnInterface = activeVpnInterface,
            syntheticDnsAddress = rootlessNetworkPlan?.syntheticDnsAddress,
            transitionState = transitionState,
            preparation = preparation,
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
        if (state is ConnectionState.Connected && !connectionManager.isUsingRootRuntime && activeConfig != null) return

        stopProcessWatchdog()
        closeVpnInterface()

        val persistedStateResult = withContext(Dispatchers.IO) { stateFile.readResult() }
        val rootModeConfigured = settingsRepo.useRootService.first()
        when {
            state is ConnectionState.Connected -> {
                if (!connectionManager.prepareSeamlessReconnect()) return
                if (
                    !connectionManager.disconnect(
                        updateState = false,
                        fastCleanup = true,
                        preserveTproxyGuard = connectionManager.hasTransitionGuard,
                    )
                ) {
                    return
                }
            }
            rootModeConfigured ||
                // An unreadable state file may still describe a live root-managed runtime, so it
                // has to be cleaned up like one instead of being mistaken for a clean slate.
                persistedStateResult is XrayStateReadResult.Unreadable ||
                persistedStateResult is XrayStateReadResult.Present &&
                persistedStateResult.state.physicalInterface != VPN_SERVICE_INTERFACE_LABEL -> {
                if (!connectionManager.ensureCleanRootRuntime()) return
            }
            else -> withContext(Dispatchers.IO) { stateFile.delete() }
        }

        val config = loadLastServerConfig()
        if (config == null) {
            alwaysOnRetryJob?.cancel()
            alwaysOnRetryJob = null
            connectionLifecycle.updateActiveConfig(null)
            val message = localizedString(R.string.connection_error_no_server_selected)
            logBuffer.append(LogSource.APP, "Always-on VPN could not start: no server is selected")
            connectionStateCoordinator.markError(message, retryable = false)
            updateNotification()
            showConnectionFailureNotification(message)
            return
        }

        connectionLifecycle.updateActiveConfig(config)
        connectWithCurrentSettings(config)
    }

    private suspend fun autoConnect() {
        if (isRunningAlwaysOnVpn()) {
            connectAlwaysOnVpn()
            return
        }

        val config = loadLastServerConfig()
        if (config == null) {
            val persistedStateResult = withContext(Dispatchers.IO) { stateFile.readResult() }
            val mayHaveRootRuntime = settingsRepo.useRootService.first() ||
                persistedStateResult is XrayStateReadResult.Unreadable ||
                persistedStateResult is XrayStateReadResult.Present &&
                persistedStateResult.state.physicalInterface != VPN_SERVICE_INTERFACE_LABEL
            if (mayHaveRootRuntime && !connectionManager.ensureCleanRootRuntime()) return
            connectionStateCoordinator.markDisconnected()
            stopSelf()
            return
        }
        connectionLifecycle.updateActiveConfig(config)
        connectWithCurrentSettings(config)
    }

    private fun scheduleAlwaysOnRetry() {
        if (alwaysOnRetryJob?.isActive == true) return
        alwaysOnRetryJob = scope.launch {
            delay(ALWAYS_ON_RETRY_DELAY_MS)
            runConnectionCommand {
                alwaysOnRetryJob = null
                if (!isRunningAlwaysOnVpn()) return@runConnectionCommand
                if (connectionStateCoordinator.state.value is ConnectionState.Connected) return@runConnectionCommand
                logBuffer.append(LogSource.APP, "Retrying always-on VPN connection...")
                val config = loadLastServerConfig()
                if (config == null) {
                    connectAlwaysOnVpn()
                } else {
                    connectionLifecycle.updateActiveConfig(config)
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

    private suspend fun reloadActiveConnection(forceFull: Boolean = true): Boolean {
        val connectionState = connectionStateCoordinator.state.value
        if (connectionState !is ConnectionState.Connected && connectionState !is ConnectionState.Error) return true

        val previousConfig = activeConfig
        val config = loadLastServerConfig() ?: previousConfig ?: return true
        val switchingServer = config != previousConfig
        connectionLifecycle.updateActiveConfig(config)

        if (connectionState is ConnectionState.Error) {
            return connectWithCurrentSettings(config)
        }

        stopProcessWatchdog()
        logBuffer.append(
            LogSource.APP,
            if (switchingServer) "Switching to ${config.name}..." else "Applying routing changes...",
        )
        connectionStateCoordinator.markApplyingRoutingChanges()
        updateNotification()
        return restartRuntime(
            config,
            ConnectionState.ApplyingRoutingChanges,
            preparation = if (switchingServer && !forceFull) {
                ConnectionPreparation.FastServerSwitch
            } else {
                ConnectionPreparation.Full
            },
        )
    }

    private suspend fun reloadAppRouting(): Boolean = executeStep(
        ConnectionStep(
            label = "Apply app routing changes",
            progress = ConnectionProgress.UpdatingAppRouting,
            isSuccessful = { it },
            action = { reloadAppRoutingOnce() },
        ),
    )

    private suspend fun reloadAppRoutingOnce(): Boolean {
        val config = activeConfig ?: return true
        val connectedState = connectionStateCoordinator.state.value as? ConnectionState.Connected
        if (connectedState == null) {
            return reloadActiveConnection()
        }
        if (!connectionManager.isUsingRootRuntime) {
            return reloadActiveConnection()
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
            return true
        }

        stopProcessWatchdog()
        logBuffer.append(LogSource.APP, "Restarting Xray to apply app routing topology changes...")
        return restartRuntime(config, ConnectionState.ApplyingRoutingChanges)
    }

    private suspend fun restoreRunningConnectionStatus(connectIfMissing: Boolean = false): Boolean = executeStep(
        ConnectionStep(
            label = "Restore running connection status",
            progress = ConnectionProgress.InspectingSavedRuntime,
            isSuccessful = { it },
            action = { restoreRunningConnectionStatusOnce(connectIfMissing) },
        ),
    )

    private suspend fun restoreRunningConnectionStatusOnce(connectIfMissing: Boolean): Boolean {
        val alreadyConnected = connectionStateCoordinator.state.value as? ConnectionState.Connected
        if (alreadyConnected != null && activeConfig != null) {
            activePhysicalNetwork = currentPhysicalNetworkSnapshot()
            handleStateSideEffects(alreadyConnected)
            updateNotification()
            scheduleNetworkRetarget("running status restored", settle = false)
            return true
        }

        val restoredState = detectRestorableRunningConnection()
        if (restoredState == null) {
            val staleState = withContext(Dispatchers.IO) { stateFile.read() }
            recoverMissingRuntime(staleState, connectIfMissing)?.let { return it }
            logBuffer.append(LogSource.APP, "No restorable running Xray state was found")
            connectionStateCoordinator.markDisconnected()
            updateNotification()
            stopSelf()
            return true
        }

        val restoredServerId = settingsRepo.lastServerId.first()
        connectionLifecycle.updateActiveConfig(loadServerConfig(restoredServerId))
        if (activeConfig == null) {
            logBuffer.append(LogSource.APP, "Stopping restored Xray runtime without selected server config")
            if (!connectionManager.disconnect(updateState = false, fastCleanup = true)) return false
            if (restoredServerId >= 0) {
                settingsRepo.compareAndSetLastServerId(restoredServerId, -1)
                activeConfigOverrideStore.clear()
            }
            connectionStateCoordinator.markDisconnected()
            updateNotification()
            stopSelf()
            return true
        }
        if (!connectionManager.restoreRootApiClients()) {
            val config = activeConfig
            if (config != null) {
                logBuffer.append(LogSource.APP, "Restarting Xray to migrate its control API")
                stopProcessWatchdog()
                return restartRuntime(config)
            }
            logBuffer.append(LogSource.APP, "Could not secure the restored Xray API; stopping Xray")
            if (!connectionManager.disconnect(updateState = false, fastCleanup = true)) return false
            connectionStateCoordinator.markDisconnected()
            updateNotification()
            stopSelf()
            return true
        }
        connectionStateCoordinator.restoreConnected(restoredState)
        activePhysicalNetwork = currentPhysicalNetworkSnapshot()
        handleStateSideEffects(restoredState)
        updateNotification()
        if (activeConfig != null) {
            scheduleNetworkRetarget("service state restored", settle = false)
        }
        return true
    }

    private suspend fun recoverMissingRuntime(staleState: XrayState?, connectIfMissing: Boolean): Boolean? {
        if (
            staleState?.rootConnectionBackend == RootConnectionBackend.Tproxy ||
            staleState?.transitionGuard != null
        ) {
            logBuffer.append(LogSource.APP, "Cleaning incomplete TPROXY runtime before reconnecting")
            var preserveGuard = connectionManager.adoptPersistedTransitionGuard()
            if (!preserveGuard && (staleState.tproxy != null || staleState.transitionGuard != null)) {
                if (!connectionManager.prepareSeamlessReconnect()) {
                    failRuntimeRestore("Could not take over the recorded TPROXY runtime")
                    return false
                }
                preserveGuard = connectionManager.hasTransitionGuard
            }
            if (!connectionManager.ensureCleanRootRuntime(preserveTproxyGuard = preserveGuard)) {
                failRuntimeRestore("Could not clean the recorded TPROXY runtime")
                return false
            }
            reconnectMissingRuntime()?.let { return it }
            connectionManager.clearFailedTransitionGuard()
        } else if (shouldCleanRecordedRootRuntime(staleState, connectIfMissing)) {
            logBuffer.append(LogSource.APP, "Cleaning incompatible recorded root runtime before reconnecting")
            if (!connectionManager.ensureCleanRootRuntime()) {
                failRuntimeRestore("Could not clean the recorded root runtime")
                return false
            }
        }
        return if (connectIfMissing) reconnectMissingRuntime() else null
    }

    private suspend fun reconnectMissingRuntime(): Boolean? {
        val config = loadLastServerConfig() ?: return null
        connectionLifecycle.updateActiveConfig(config)
        return connectWithCurrentSettings(config)
    }

    private fun failRuntimeRestore(message: String) {
        logBuffer.append(LogSource.APP, message)
        connectionStateCoordinator.markError(message)
        updateNotification()
    }

    private suspend fun detectRestorableRunningConnection(): ConnectionState.Connected? = withContext(Dispatchers.IO) {
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        if (!runtimeSettings.useRootService) return@withContext null

        val state = stateFile.read() ?: return@withContext null
        if (!isRuntimeVersionCompatible(state.appVersionCode, currentAppVersionCode())) return@withContext null
        if (state.rootConnectionBackend != runtimeSettings.rootConnectionBackend) return@withContext null
        if (state.xrayPid <= 0) return@withContext null
        if (!connectionManager.isRestorableRootProcessAlive(state.xrayPid)) return@withContext null
        if (
            !connectionManager.isRestorableRootRoutingAvailable(
                state,
                TunInterfaceDetector.isInterfaceUp(state.tunName),
            )
        ) {
            return@withContext null
        }

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
        return loadServerConfig(lastServerId)
    }

    private fun currentAppVersionCode(): Long = PackageInfoCompat.getLongVersionCode(
        packageManager.getPackageInfo(packageName, 0),
    )

    private suspend fun loadServerConfig(serverId: Long): ServerConfig? {
        if (serverId < 0) return null
        val serverEntity = serverRepository.getById(serverId) ?: return null
        return runCatching { serverRepository.parseConfig(serverEntity) }.getOrNull()
    }

    private fun handleStateSideEffects(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                startProcessWatchdog(state)
                updateBalancerSelectionTracker()
            }
            else -> {
                stopBalancerSelectionTracker()
                stopProcessWatchdog()
                stopMetrics()
            }
        }
        updatePingTracker()
        updateMetricsJob()
    }

    private fun startBalancerSelectionTracker() {
        val balancerTag = activeConfig?.primaryBalancerTag()
        if (balancerTag == null) {
            pauseBalancerSelectionTracker()
            return
        }
        if (balancerSelectionJob?.isActive == true && balancerSelectionTag == balancerTag) return

        pauseBalancerSelectionTracker()
        balancerSelectionTag = balancerTag

        balancerSelectionJob = scope.launch(Dispatchers.IO) {
            while (isActive && connectionStateCoordinator.state.value is ConnectionState.Connected) {
                val selection = connectionManager.readBalancerSelection(balancerTag)
                connectionStateCoordinator.updateActiveBalancerSelection(selection)
                delay(BALANCER_SELECTION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopBalancerSelectionTracker() {
        pauseBalancerSelectionTracker()
        connectionStateCoordinator.updateActiveBalancerSelection(null)
    }

    private fun pauseBalancerSelectionTracker() {
        balancerSelectionJob?.cancel()
        balancerSelectionJob = null
        balancerSelectionTag = null
    }

    private fun updateBalancerSelectionTracker() {
        val hasUiSubscriber = connectionStateCoordinator.activeBalancerSelectionSubscribers.value > 0
        if (
            screenInteractive &&
            hasUiSubscriber &&
            connectionStateCoordinator.state.value is ConnectionState.Connected
        ) {
            startBalancerSelectionTracker()
        } else {
            pauseBalancerSelectionTracker()
        }
    }

    /**
     * Keeps a single round-trip measurement for whatever the tunnel is using, shared by the
     * connection banner and the notification so neither probes the endpoint on its own.
     *
     * It runs far slower than the metrics poll: a reading costs a bare TCP connect the server sees,
     * and at the metrics cadence that would be a connect every second.
     */
    private fun updatePingTracker() {
        val wanted = notificationSettings.needsPingProbe || connectionStateCoordinator.activePingSubscribers.value > 0
        if (
            screenInteractive &&
            wanted &&
            connectionStateCoordinator.state.value is ConnectionState.Connected
        ) {
            startPingTracker()
        } else {
            pausePingTracker()
        }
    }

    private fun startPingTracker() {
        if (pingJob?.isActive == true) return

        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && connectionStateCoordinator.state.value is ConnectionState.Connected) {
                val latencyMs = measureActivePing()
                connectionStateCoordinator.updateActivePing(latencyMs)
                // The ping field can be the only one enabled, in which case the metrics loop is not
                // running and this is the only thing that can put the reading in the notification.
                if (notificationSettings.showPing && notificationMetrics.pingMs != latencyMs) {
                    withContext(Dispatchers.Main) {
                        notificationMetrics = notificationMetrics.copy(pingMs = latencyMs)
                        updateNotification()
                    }
                }
                delay(PING_POLL_INTERVAL_MS)
            }
        }
    }

    private fun pausePingTracker() {
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * A balancer reports the delay its observatory measured for the outbound it picked, which beats
     * probing an endpoint it may not be using. A config with several proxy outbounds reports
     * nothing at all, because its recorded address is only the first outbound that was parsed and a
     * probe of it would be labelled as the connection's own latency.
     */
    private suspend fun measureActivePing(): Int? {
        val config = activeConfig ?: return null
        val balancerTag = config.primaryBalancerTag()
        if (balancerTag != null) {
            return connectionManager.readBalancerSelection(balancerTag)?.latencyMs?.toInt()
        }
        if (config.proxyOutboundCount() != null) return null
        return serverLatencyTester.measureTcping(config).takeIf { it >= 0 }
    }

    private fun startProcessWatchdog(state: ConnectionState.Connected) {
        if (healthWatchdog.start(state)) {
            lastNetworkSafetyCheckAtMs = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun runtimeModeRecoveryReason(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val alwaysOnVpn = isAlwaysOn
        if (alwaysOnVpnState.active.value != alwaysOnVpn) {
            alwaysOnVpnState.update(alwaysOnVpn)
            withContext(Dispatchers.Main) {
                lastNotificationContent = null
                updateNotification()
            }
        }
        val shouldRunRootService = (rootServiceRequested ?: return null) && !alwaysOnVpn
        if (connectionManager.isUsingRootRuntime == shouldRunRootService) return null

        return if (alwaysOnVpn) {
            "Always-on VPN enabled; switching to Android VpnService..."
        } else {
            "Always-on VPN disabled; restoring the configured service mode..."
        }
    }

    private suspend fun maybeScheduleNetworkSafetyCheck() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkSafetyCheckAtMs < NETWORK_SAFETY_CHECK_INTERVAL_MS) return
        lastNetworkSafetyCheckAtMs = now

        withContext(Dispatchers.Main.immediate) {
            if (!connectionManager.isUsingRootRuntime || activeConfig == null) return@withContext

            val currentNetwork = currentPhysicalNetworkSnapshot()
            val networkChanged = currentNetwork != null && activePhysicalNetwork?.sameNetwork(currentNetwork) != true
            if (!shouldVerifyRootRoute(passiveHealthMonitoringEnabled, networkChanged, networkCallbacksAvailable)) {
                return@withContext
            }
            val reason = when {
                networkChanged -> "watchdog observed a new network"
                !networkCallbacksAvailable -> "network callback fallback"
                else -> PERIODIC_ROOT_ROUTE_VERIFICATION_REASON
            }
            scheduleNetworkRetarget(reason = reason, settle = false)
        }
    }

    private fun stopProcessWatchdog() {
        if (::healthWatchdog.isInitialized) healthWatchdog.stop()
    }

    /**
     * One loop serves both the notification and the connection banner, because both want the same
     * Xray counters and polling them twice would double the gRPC traffic for no extra information.
     */
    private fun updateMetricsJob() {
        val connectedState = connectionStateCoordinator.state.value as? ConnectionState.Connected
        val intervalMs = metricsPollIntervalMs(
            notificationIntervalMs = notificationSettings.updateIntervalMs,
            notificationWantsMetrics = notificationSettings.needsMetricsPoll,
            uiWantsSessionTraffic = uiWantsSessionTraffic(),
        )
        if (connectedState == null || intervalMs == null) {
            stopMetrics()
        } else if (screenInteractive) {
            startMetrics(connectedState, intervalMs)
        } else {
            pauseMetrics()
        }
    }

    private fun uiWantsSessionTraffic(): Boolean = connectionStateCoordinator.sessionTrafficSubscribers.value > 0

    private fun startMetrics(state: ConnectionState.Connected, intervalMs: Int) {
        // Every input that feeds the interval routes back through updateMetricsJob, which restarts
        // the loop whenever the answer changes, so the loop can hold the interval it started with.
        if (metricsJob?.isActive == true && metricsIntervalMs == intervalMs) return

        pauseMetrics()
        metricsIntervalMs = intervalMs
        metricsJob = scope.launch(Dispatchers.IO) {
            // The previous traffic sample is owned by this coroutine: sharing it as a field let a
            // cancelled loop's late write leak a stale sample into the next metrics session.
            //
            // It is primed before the first tick because a rate needs two samples: without this
            // the loop's opening reading would carry byte totals but no speeds, and reopening the
            // home screen would dash both rate cells for a whole interval.
            var previousSample: TrafficSample? = primingSample()
            if (previousSample != null) delay(METRICS_PRIMING_DELAY_MS)
            var nextNotificationUpdateAtMs = 0L
            while (isActive) {
                val connectedState = connectionStateCoordinator.state.value as? ConnectionState.Connected ?: break
                val reading = readMetrics(connectedState, previousSample)
                previousSample = reading.trafficSample
                // Only a real reading is published. A tick that read no traffic at all must not
                // wipe the figures the banner is showing, and a disconnect clears them anyway.
                reading.sessionTraffic?.let(connectionStateCoordinator::updateSessionTraffic)
                val nowMs = SystemClock.elapsedRealtime()
                if (reading.metrics != notificationMetrics && nowMs >= nextNotificationUpdateAtMs) {
                    nextNotificationUpdateAtMs = nowMs + notificationSettings.updateIntervalMs
                    withContext(Dispatchers.Main) {
                        notificationMetrics = reading.metrics
                        updateNotification()
                    }
                }
                delay(intervalMs.toLong())
            }
        }
        if (state.corePid <= 0) stopMetrics()
    }

    /**
     * Stops the loop without clearing the last session traffic reading: the poll also stops when
     * the banner simply goes off screen, and the connection it measured is still running. A state
     * that is no longer connected clears the reading in the coordinator itself.
     */
    private fun stopMetrics() {
        pauseMetrics()
        notificationMetrics = NotificationMetrics()
    }

    private fun pauseMetrics() {
        metricsJob?.cancel()
        metricsJob = null
        metricsIntervalMs = 0
    }

    private suspend fun readMetrics(
        state: ConnectionState.Connected,
        previousSample: TrafficSample?,
    ): MetricsReading {
        val settings = notificationSettings
        val wantsTraffic = settings.showTrafficSpeed || settings.showSessionTraffic || uiWantsSessionTraffic()
        val trafficReading = if (wantsTraffic) {
            readTraffic(previousSample)
        } else {
            TrafficReading(speeds = null, sample = null)
        }
        // The banner can be the only reason traffic was read at all, so the notification metrics
        // must stay empty unless the user asked the notification for them: a field it changes
        // every tick would rebuild the notification text once per poll for nothing.
        val notificationSpeeds = trafficReading.speeds?.takeIf { settings.showTrafficSpeed }
        val processMetrics = if (settings.showRamUsage && settings.showConnectionCount) {
            connectionManager.readProcessMetrics(state.corePid)
        } else {
            null
        }
        val ramMb = if (settings.showRamUsage) {
            if (processMetrics == null) {
                connectionManager.readProcessResidentMemoryMb(state.corePid)
            } else {
                processMetrics.residentMemoryMb
            }
        } else {
            null
        }
        val connectionCount = if (settings.showConnectionCount) {
            if (processMetrics == null) {
                connectionManager.readActiveConnectionCount(state.corePid)
            } else {
                processMetrics.activeConnectionCount
            }
        } else {
            null
        }
        // The totals need no second sample, so the session field reports from the very first tick.
        val sessionTotals = trafficReading.sample?.totals?.takeIf { settings.showSessionTraffic }
        return MetricsReading(
            metrics = NotificationMetrics(
                proxyBps = notificationSpeeds?.proxyBps,
                directBps = notificationSpeeds?.directBps,
                ramMb = ramMb,
                connectionCount = connectionCount,
                pingMs = connectionStateCoordinator.activePingMs.value.takeIf { settings.showPing },
                sessionUplinkBytes = sessionTotals?.proxyUplinkBytes,
                sessionDownlinkBytes = sessionTotals?.proxyDownlinkBytes,
            ),
            sessionTraffic = trafficReading.sessionTraffic(),
            trafficSample = trafficReading.sample,
        )
    }

    /**
     * A first traffic sample to measure the loop's opening rates against, or `null` when no
     * consumer wants traffic and the read would be a wasted round trip.
     */
    private suspend fun primingSample(): TrafficSample? {
        if (!notificationSettings.showTrafficSpeed && !uiWantsSessionTraffic()) return null
        return readTraffic(previous = null).sample
    }

    private suspend fun readTraffic(previous: TrafficSample?): TrafficReading {
        val totals = connectionManager.readOutboundTrafficStatsBytes().readOutboundTraffic()
            ?: return TrafficReading(speeds = null, sample = null)
        // Rates are measured against the monotonic clock: a wall-clock correction between two
        // samples would otherwise print a rate that never happened.
        val sample = TrafficSample(elapsedRealtimeMs = SystemClock.elapsedRealtime(), totals = totals)
        if (previous == null) return TrafficReading(speeds = null, sample = sample)
        val elapsedMs = sample.elapsedRealtimeMs - previous.elapsedRealtimeMs
        return TrafficReading(
            speeds = TrafficSpeeds(
                directBps = bytesPerSecond(sample.totals.directBytes, previous.totals.directBytes, elapsedMs),
                uplinkBps = bytesPerSecond(sample.totals.proxyUplinkBytes, previous.totals.proxyUplinkBytes, elapsedMs),
                downlinkBps = bytesPerSecond(sample.totals.proxyDownlinkBytes, previous.totals.proxyDownlinkBytes, elapsedMs),
            ),
            sample = sample,
        )
    }

    @Synchronized
    private fun recoverNativeProcess(
        reason: String,
        watchedPid: Int,
        pidToKill: Int? = null,
    ): Boolean {
        if (!isConnectedProcess(watchedPid)) return false
        if (processRecoveryJob?.isActive == true) return true

        processRecoveryJob = scope.launch {
            runConnectionCommand {
                if (!isConnectedProcess(watchedPid)) return@runConnectionCommand
                val config = activeConfig ?: return@runConnectionCommand
                executeStep(
                    ConnectionStep(
                        "Recover Xray runtime",
                        ConnectionProgress.StoppingCore,
                        isSuccessful = { it },
                        action = {
                            stopProcessWatchdog()
                            logBuffer.append(LogSource.APP, reason)

                            if (pidToKill != null) {
                                connectionManager.killProcess(pidToKill, signal = 9)
                            }

                            connectionStateCoordinator.startConnection(ConnectionState.Connecting)
                            updateNotification(localizedString(R.string.notification_status_recovering_core))
                            restartRuntime(config, reconnectDelayMs = PROCESS_RESTART_DELAY_MS)
                        },
                    ),
                )
            }
        }
        return true
    }

    private fun isConnectedProcess(watchedPid: Int): Boolean {
        val currentPid = (connectionStateCoordinator.state.value as? ConnectionState.Connected)?.corePid
        return currentPid == watchedPid
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

    private fun acquireConnectionCommandWakeLock() {
        runCatching {
            connectionCommandWakeLock.acquire(CONNECTION_COMMAND_WAKE_LOCK_TIMEOUT_MS)
        }.onFailure { error ->
            logBuffer.append(LogSource.APP, "Could not hold CPU awake for connection command: ${error.message}")
        }
    }

    private fun releaseConnectionCommandWakeLock() {
        runCatching {
            if (connectionCommandWakeLock.isHeld) connectionCommandWakeLock.release()
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
        if (!connectionManager.isUsingRootRuntime) return
        activeConfig ?: return
        networkRetargetWorker.signal(
            reason = reason,
            settle = settle,
            passive = reason == PERIODIC_ROOT_ROUTE_VERIFICATION_REASON,
        )
    }

    private suspend fun retargetNetworkUntilStable(reason: String) {
        executeStep(
            ConnectionStep(
                label = "Stabilize physical network route",
                isSuccessful = { it != NetworkRetargetRetryOutcome.Exhausted },
                slowSuccessLogThresholdMs = SLOW_NETWORK_STABILIZATION_LOG_THRESHOLD_MS,
                action = { retargetNetworkUntilStableOnce(reason) },
            ),
        )
    }

    private suspend fun retargetNetworkUntilStableOnce(reason: String): NetworkRetargetRetryOutcome {
        val requiresPassiveMonitoring = reason == PERIODIC_ROOT_ROUTE_VERIFICATION_REASON
        if (requiresPassiveMonitoring && !passiveHealthMonitoringEnabled) return NetworkRetargetRetryOutcome.Stopped
        val outcome = retryNetworkRetarget(
            retryDelaysMs = NETWORK_RETARGET_RETRY_DELAYS_MS,
            shouldContinue = {
                connectionManager.isUsingRootRuntime &&
                    activeConfig != null &&
                    (!requiresPassiveMonitoring || passiveHealthMonitoringEnabled)
            },
        ) { attempt ->
            retargetNetwork(reason, attempt)
        }
        if (
            outcome == NetworkRetargetRetryOutcome.Exhausted &&
            connectionManager.isUsingRootRuntime &&
            activeConfig != null
        ) {
            logBuffer.append(LogSource.APP, "Network changed ($reason), but no usable physical route appeared")
            updateNotification(localizedString(R.string.notification_status_waiting_for_physical_route))
        }
        return outcome
    }

    private suspend fun retargetNetwork(reason: String, attempt: Int): NetworkRetargetResult = runConnectionCommand {
        if (reason == PERIODIC_ROOT_ROUTE_VERIFICATION_REASON && !passiveHealthMonitoringEnabled) {
            return@runConnectionCommand NetworkRetargetResult.Done
        }
        if (!connectionManager.isUsingRootRuntime) return@runConnectionCommand NetworkRetargetResult.Done
        val latestConfig = activeConfig ?: return@runConnectionCommand NetworkRetargetResult.Done
        val latestState = connectionStateCoordinator.state.value as? ConnectionState.Connected
            ?: return@runConnectionCommand NetworkRetargetResult.Done
        val previousNetwork = activePhysicalNetwork
        val currentNetwork = currentPhysicalNetworkSnapshot()
        val currentRoute = withContext(Dispatchers.IO) {
            connectionManager.detectPhysicalRoute(latestState.tunName)
        }
        if (reason == PERIODIC_ROOT_ROUTE_VERIFICATION_REASON && !passiveHealthMonitoringEnabled) {
            return@runConnectionCommand NetworkRetargetResult.Done
        }

        if (currentRoute == null) {
            if (attempt == 1) {
                logBuffer.append(LogSource.APP, "Network changed ($reason), waiting for a usable physical route")
            }
            updateNotification(localizedString(R.string.notification_status_waiting_for_physical_route))
            return@runConnectionCommand NetworkRetargetResult.Retry
        }

        val physicalRouteChanged = !currentRoute.matches(latestState)
        if (shouldWaitForMatchingPhysicalNetwork(reason, attempt, previousNetwork, currentNetwork, currentRoute)) {
            return@runConnectionCommand NetworkRetargetResult.Retry
        }

        if (!physicalRouteChanged) {
            activePhysicalNetwork = currentNetwork ?: previousNetwork
            updateNotification()
            return@runConnectionCommand NetworkRetargetResult.Done
        }

        if (
            shouldReconnectForNetworkChange(
                previousInterface = latestState.physicalInterface,
                currentInterface = currentRoute.dev,
            )
        ) {
            logBuffer.append(
                LogSource.APP,
                "Network changed ($reason): ${describeNetworkChange(previousNetwork, currentNetwork)}, " +
                    "${latestState.physicalInterface} -> ${currentRoute.describe()}, reconnecting...",
            )
            reconnectForPhysicalRouteChange(latestConfig, latestState, currentRoute)
            return@runConnectionCommand NetworkRetargetResult.Done
        }

        refreshPhysicalRoutingForNetworkChange(
            reason = reason,
            latestConfig = latestConfig,
            latestState = latestState,
            currentRoute = currentRoute,
            currentNetwork = currentNetwork,
            previousNetwork = previousNetwork,
        )
    }

    private suspend fun refreshPhysicalRoutingForNetworkChange(
        reason: String,
        latestConfig: ServerConfig,
        latestState: ConnectionState.Connected,
        currentRoute: TunManager.PhysicalRoute,
        currentNetwork: PhysicalNetworkSnapshot?,
        previousNetwork: PhysicalNetworkSnapshot?,
    ): NetworkRetargetResult {
        logBuffer.append(
            LogSource.APP,
            "Network route changed ($reason): ${latestState.describePhysicalRoute()} -> " +
                "${currentRoute.describe()}, refreshing routing...",
        )
        updateNotification(localizedString(R.string.notification_status_refreshing_physical_route))
        val runtimeSettings = settingsRepo.runtimeSettingsSnapshot()
        val result = withContext(Dispatchers.IO) {
            connectionManager.updatePhysicalBypassRoute(
                connectedState = latestState,
                physicalRoute = currentRoute,
                runtimeSettings = runtimeSettings,
            )
        }
        return when (result) {
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
            PhysicalRouteUpdateResult.RequiresReconnect -> {
                if (reason == PERIODIC_ROOT_ROUTE_VERIFICATION_REASON && !passiveHealthMonitoringEnabled) {
                    updateNotification()
                    return NetworkRetargetResult.Done
                }
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
        stopProcessWatchdog()
        connectionStateCoordinator.startConnection(ConnectionState.Connecting)
        restartRuntime(config)
    }

    private suspend fun setupVpnInterface(
        runtimeSettings: XrayRuntimeSettings,
        networkPlan: RootlessVpnNetworkPlan,
    ): ParcelFileDescriptor? {
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

        networkPlan.addresses.forEach { prefix ->
            builder.addAddress(prefix.address, prefix.prefixLength)
        }
        networkPlan.routes.forEach { prefix ->
            builder.addRoute(prefix.address, prefix.prefixLength)
        }
        networkPlan.dnsServers.forEach(builder::addDnsServer)

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

    /**
     * Tears the runtime down after a connection command failed for a reason nothing anticipated.
     *
     * Closing the tunnel descriptor is not enough on its own: the rootless core holds a duplicate
     * of it and a root-managed core owns the routing outright, so the runtime is stopped first.
     * The failure is then reported the same way an exhausted connection attempt is, which keeps
     * an always-on VPN retrying instead of silently staying down.
     */
    private suspend fun handleUnexpectedCommandFailure(error: Throwable) {
        val description = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        logBuffer.append(LogSource.APP, "Connection command failed unexpectedly: $description")
        val message = localizedString(R.string.notification_unknown_connection_error)
        stopProcessWatchdog()
        withContext(NonCancellable) {
            runCatching { connectionManager.disconnect(updateState = false, fastCleanup = true) }
        }
        closeVpnInterface()
        connectionStateCoordinator.markError(message, retryable = true)
        if (isRunningAlwaysOnVpn()) scheduleAlwaysOnRetry() else showConnectionFailureNotification(message)
    }

    override fun onRevoke() {
        alwaysOnVpnState.update(false)
        alwaysOnRetryJob?.cancel()
        alwaysOnRetryJob = null
        launchConnectionCommand {
            connectionLifecycle.updateActiveConfig(null)
            activePhysicalNetwork = null
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
        if (state is ConnectionState.Error && terminalFailureNotificationShown) {
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
        terminalFailureNotificationShown = true
        lastNotificationContent = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)

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
        val baseText = when {
            state.physicalInterface == VPN_SERVICE_INTERFACE_LABEL -> localizedString(R.string.notification_vpn_service_active)
            state.tunName == TPROXY_INTERFACE_LABEL -> localizedString(
                R.string.notification_root_tproxy_active,
                state.physicalInterface,
            )
            else -> localizedString(
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
            NotificationField.Ping -> {
                val ping = metrics.pingMs?.let(::formatMilliseconds)
                    ?: localizedString(R.string.notification_metric_unavailable)
                if (compact) {
                    localizedString(R.string.notification_ping_compact, ping)
                } else {
                    localizedString(R.string.notification_ping_expanded, ping)
                }
            }
            NotificationField.SessionTraffic -> {
                val downloaded = formatNullableBytes(metrics.sessionDownlinkBytes)
                val uploaded = formatNullableBytes(metrics.sessionUplinkBytes)
                if (compact) {
                    localizedString(R.string.notification_session_compact, downloaded, uploaded)
                } else {
                    localizedString(R.string.notification_session_expanded, downloaded, uploaded)
                }
            }
        }
    }

    private fun formatNullableBytesPerSecond(bytesPerSecond: Long?): String = bytesPerSecond?.let(::formatBytesPerSecond)
        ?: localizedString(R.string.notification_metric_unavailable)

    private fun formatBytesPerSecond(bytesPerSecond: Long): String {
        val scaled = scaleBytes(bytesPerSecond, appLocale())
        return localizedString(R.string.value_with_unit, scaled.value, localizedString(scaled.magnitude.rateUnit()))
    }

    private fun formatNullableBytes(bytes: Long?): String = bytes?.let(::formatBytes)
        ?: localizedString(R.string.notification_metric_unavailable)

    private fun formatBytes(bytes: Long): String {
        val scaled = scaleBytes(bytes, appLocale())
        return localizedString(R.string.value_with_unit, scaled.value, localizedString(scaled.magnitude.sizeUnit()))
    }

    private fun formatMilliseconds(value: Int): String = localizedString(
        R.string.value_with_unit,
        formatInteger(value.toLong()),
        localizedString(R.string.unit_milliseconds),
    )

    private fun formatMebibytes(value: Long): String = localizedString(
        R.string.value_with_unit,
        formatInteger(value),
        localizedString(R.string.traffic_unit_mebibytes),
    )

    private fun formatInteger(value: Long): String = NumberFormat.getIntegerInstance(appLocale()).format(value)

    private fun appLocale(): Locale = forAppLanguage().resources.configuration.locales[0]

    private fun startAsForeground(title: String, text: String, showDisconnectAction: Boolean) {
        terminalFailureNotificationShown = false
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
            .setOngoing(true)

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
        val pingMs: Int? = null,
        val sessionUplinkBytes: Long? = null,
        val sessionDownlinkBytes: Long? = null,
    )

    private data class NotificationContent(
        val title: String,
        val text: String,
        val showDisconnectAction: Boolean,
    )

    private data class TrafficSample(
        val elapsedRealtimeMs: Long,
        val totals: OutboundTrafficTotals,
    )

    private data class TrafficSpeeds(
        val directBps: Long,
        val uplinkBps: Long,
        val downlinkBps: Long,
    ) {
        val proxyBps: Long get() = uplinkBps + downlinkBps
    }

    private data class MetricsReading(
        val metrics: NotificationMetrics,
        val sessionTraffic: SessionTrafficMetrics?,
        val trafficSample: TrafficSample?,
    )

    private data class TrafficReading(
        val speeds: TrafficSpeeds?,
        val sample: TrafficSample?,
    ) {
        /**
         * A reading is published whole or not at all.
         *
         * A rate needs two samples, so a tick with nothing to compare against has byte totals but
         * no speeds. Publishing that half reading would blank the rate cells of a banner that is
         * already showing figures, which reads as a flicker; withholding it leaves the previous
         * complete reading in place until a real one replaces it.
         */
        fun sessionTraffic(): SessionTrafficMetrics? {
            val totals = sample?.totals ?: return null
            val speeds = speeds ?: return null
            return SessionTrafficMetrics(
                uplinkBps = speeds.uplinkBps,
                downlinkBps = speeds.downlinkBps,
                uplinkBytes = totals.proxyUplinkBytes,
                downlinkBytes = totals.proxyDownlinkBytes,
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "xray_service"
        const val FAILURE_CHANNEL_ID = "xray_connection_failures"
        const val NOTIFICATION_ID = 1
        const val FAILURE_NOTIFICATION_ID = 2
        const val ACTION_CONNECT = "com.material.xray.CONNECT"
        private const val ACTION_AUTO_CONNECT = "com.material.xray.AUTO_CONNECT"
        private const val ACTION_RECOVER_AFTER_PACKAGE_REPLACEMENT =
            "com.material.xray.RECOVER_AFTER_PACKAGE_REPLACEMENT"
        const val ACTION_SWITCH_SERVER = "com.material.xray.SWITCH_SERVER"
        const val ACTION_DISCONNECT = "com.material.xray.DISCONNECT"
        private const val ACTION_FORCE_DISCONNECT = "com.material.xray.FORCE_DISCONNECT"
        const val ACTION_RELOAD = "com.material.xray.RELOAD"
        const val ACTION_RELOAD_APP_ROUTING = "com.material.xray.RELOAD_APP_ROUTING"
        const val ACTION_RESTORE_STATUS = "com.material.xray.RESTORE_STATUS"
        const val EXTRA_SERVER_CONFIG = "server_config"
        private const val NETWORK_RETARGET_SETTLE_DELAY_MS = 250L
        private const val SLOW_NETWORK_STABILIZATION_LOG_THRESHOLD_MS = 500L
        private const val NETWORK_RETARGET_WAKE_LOCK_TIMEOUT_MS = 30_000L
        private const val CONNECTION_COMMAND_WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val NETWORK_SAFETY_CHECK_INTERVAL_MS = 60_000L
        private const val PERIODIC_ROOT_ROUTE_VERIFICATION_REASON = "periodic root route verification"
        private val NETWORK_RETARGET_RETRY_DELAYS_MS = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L)
        private const val LOCAL_API_HEALTH_PROBE_INTERVAL_MS = 60_000L
        private const val MEMORY_HEALTH_CHECK_INTERVAL_MS = 10_000L
        private const val LOCAL_HEALTH_SNAPSHOT_INTERVAL_MS = 5 * 60_000L
        private const val TUNNEL_FAILURE_THRESHOLD = 2
        private const val LOCAL_API_FAILURE_THRESHOLD = 3
        private const val HEALTH_CHECK_FAILURE_LOG_THRESHOLD = 3
        private const val ALWAYS_ON_RETRY_DELAY_MS = 30_000L
        private const val PROCESS_RESTART_DELAY_MS = 2_000L
        private const val PROCESS_WATCHDOG_INTERVAL_MS = 10_000L
        private const val BALANCER_SELECTION_POLL_INTERVAL_MS = 5_000L
        private const val METRICS_PRIMING_DELAY_MS = 250L
        private const val RECONFIGURE_SETTLE_DELAY_MS = 200L
        private const val PING_POLL_INTERVAL_MS = 10_000L
        private const val ROOTLESS_TUN_NAME = "tun0"
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

        fun autoConnect(context: Context) {
            context.startForegroundService(
                Intent(context, XrayService::class.java).setAction(ACTION_AUTO_CONNECT),
            )
        }

        fun recoverAfterPackageReplacement(context: Context) {
            context.startForegroundService(
                Intent(context, XrayService::class.java).setAction(ACTION_RECOVER_AFTER_PACKAGE_REPLACEMENT),
            )
        }

        fun switchServer(context: Context) {
            context.startForegroundService(
                Intent(context, XrayService::class.java).setAction(ACTION_SWITCH_SERVER),
            )
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

internal fun effectiveTproxyIpv6(
    requested: Boolean,
    useRootService: Boolean,
    backend: RootConnectionBackend,
    compatibility: TproxyCompatibility,
): Boolean = requested &&
    !(
        useRootService &&
            backend == RootConnectionBackend.Tproxy &&
            compatibility is TproxyCompatibility.Supported &&
            !compatibility.ipv6
        )

internal fun isRuntimeVersionCompatible(recordedVersionCode: Long?, currentVersionCode: Long): Boolean = recordedVersionCode == currentVersionCode

internal fun shouldCleanRecordedRootRuntime(state: XrayState?, connectIfMissing: Boolean): Boolean = connectIfMissing && state != null && state.physicalInterface != VPN_SERVICE_INTERFACE_LABEL

internal fun shouldVerifyRootRoute(
    passiveHealthMonitoringEnabled: Boolean,
    networkChanged: Boolean,
    networkCallbacksAvailable: Boolean,
): Boolean = passiveHealthMonitoringEnabled || networkChanged || !networkCallbacksAvailable

internal fun shouldReconnectForNetworkChange(
    previousInterface: String,
    currentInterface: String,
): Boolean = currentInterface != previousInterface

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

/**
 * Reports whether this state describes a tunnel owned by a root-managed core, which survives the
 * service that started it.
 */
private fun ConnectionState.describesRootManagedTunnel(): Boolean = this is ConnectionState.Connected && physicalInterface != VPN_SERVICE_INTERFACE_LABEL
