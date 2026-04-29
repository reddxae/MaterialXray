package com.material.xray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.material.xray.MainActivity
import com.material.xray.core.network.CaptivePortalDetector
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class XrayService : Service() {

    @Inject lateinit var rootShell: RootShell
    @Inject lateinit var captivePortalDetector: CaptivePortalDetector
    @Inject lateinit var appBypassDao: AppBypassDao
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var connectionStateHolder: ConnectionStateHolder
    @Inject lateinit var logBuffer: LogBuffer
    @Inject lateinit var geoDataManager: GeoDataManager

    private lateinit var connectionManager: ConnectionManager
    private lateinit var xrayLogStreamer: XrayLogStreamer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeConfig: ServerConfig? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkReconnectJob: Job? = null
    private var processWatchdogJob: Job? = null
    private var processWatchdogPid: Int? = null
    private var processRecoveryJob: Job? = null
    private val connectionCommandMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground("Material Xray", "Starting...", showDisconnectAction = false)

        xrayLogStreamer = XrayLogStreamer(filesDir.resolve("xray.log"), logBuffer)
        connectionManager = ConnectionManager(
            context = this,
            shell = rootShell,
            captivePortalDetector = captivePortalDetector,
            configGenerator = ConfigGenerator(),
            geoDataManager = geoDataManager,
            appBypassDao = appBypassDao,
            serverRepository = serverRepository,
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
            ACTION_DISCONNECT -> {
                launchConnectionCommand {
                    activeConfig = null
                    networkReconnectJob?.cancel()
                    stopLogTail()
                    stopProcessWatchdog()
                    connectionManager.disconnect()
                    stopSelf()
                }
            }
            ACTION_RELOAD -> {
                launchConnectionCommand { reloadActiveConnection() }
            }
            ACTION_RELOAD_APP_ROUTING -> {
                launchConnectionCommand { reloadAppRouting() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        activeConfig = null
        networkReconnectJob?.cancel()
        processRecoveryJob?.cancel()
        stopProcessWatchdog()
        stopLogTail()
        scope.cancel()
        runBlocking {
            connectionManager.disconnect(updateState = false)
        }
        rootShell.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    ) {
        val tunName = settingsRepo.tunName.first()
        val fwmark = settingsRepo.fwmark.first()
        val routeTable = settingsRepo.routeTable.first()
        val dns = settingsRepo.dnsServers.first()
        val logLevel = settingsRepo.xrayLogLevel.first()
        val defaultOutbound = settingsRepo.defaultOutbound.first()
        val bypassLan = settingsRepo.bypassLan.first()
        val routingRules = settingsRepo.routingRules.first()
        connectionManager.connect(
            server = config,
            tunName = tunName,
            fwmark = fwmark,
            routeTable = routeTable,
            dnsServers = dns,
            logLevel = logLevel,
            defaultOutbound = defaultOutbound,
            bypassLan = bypassLan,
            routingRules = routingRules,
            transitionState = transitionState,
            cleanStateFirst = cleanStateFirst,
        )
    }

    private suspend fun reloadActiveConnection() {
        val config = activeConfig ?: return
        networkReconnectJob?.cancel()
        stopLogTail()
        logBuffer.append(LogSource.APP, "Applying routing changes...")
        connectionStateHolder.update(ConnectionState.ApplyingRoutingChanges)
        updateNotification()
        connectionManager.disconnect(updateState = false)
        connectWithCurrentSettings(config, ConnectionState.ApplyingRoutingChanges, cleanStateFirst = false)
    }

    private suspend fun reloadAppRouting() {
        val config = activeConfig ?: return
        val connectedState = connectionStateHolder.state.value as? ConnectionState.Connected
        if (connectedState == null) {
            reloadActiveConnection()
            return
        }

        networkReconnectJob?.cancel()
        logBuffer.append(LogSource.APP, "Applying app routing changes...")
        connectionStateHolder.update(ConnectionState.ApplyingRoutingChanges)
        updateNotification()

        val fastApplied = connectionManager.applyAppRoutingChanges(
            connectedState = connectedState,
            tunName = settingsRepo.tunName.first(),
            fwmark = settingsRepo.fwmark.first(),
            routeTable = settingsRepo.routeTable.first(),
        )
        if (fastApplied) {
            connectionStateHolder.update(connectedState)
            return
        }

        stopLogTail()
        logBuffer.append(LogSource.APP, "Restarting Xray to apply app routing topology changes...")
        connectionManager.disconnect(updateState = false)
        connectWithCurrentSettings(config, ConnectionState.ApplyingRoutingChanges, cleanStateFirst = false)
    }

    private fun handleStateSideEffects(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> startProcessWatchdog(state)
            else -> stopProcessWatchdog()
        }
    }

    private fun startProcessWatchdog(state: ConnectionState.Connected) {
        if (processWatchdogPid == state.corePid && processWatchdogJob?.isActive == true) return

        stopProcessWatchdog()
        processWatchdogPid = state.corePid
        processWatchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PROCESS_WATCHDOG_INTERVAL_MS)

                val currentState = connectionStateHolder.state.value as? ConnectionState.Connected ?: break
                val pid = currentState.corePid
                if (!connectionManager.isProcessAlive(pid)) {
                    recoverNativeProcess(
                        reason = "xray process $pid exited unexpectedly; reconnecting...",
                    )
                    break
                }

                val residentMemoryMb = connectionManager.readProcessResidentMemoryMb(pid) ?: continue
                if (residentMemoryMb > MAX_XRAY_PROCESS_MEMORY_MB) {
                    recoverNativeProcess(
                        reason = "xray process $pid exceeded ${MAX_XRAY_PROCESS_MEMORY_MB} MiB RSS (${residentMemoryMb} MiB); restarting...",
                        pidToKill = pid,
                    )
                    break
                }
            }
        }
    }

    private fun stopProcessWatchdog() {
        processWatchdogJob?.cancel()
        processWatchdogJob = null
        processWatchdogPid = null
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
                connectionManager.disconnect(updateState = false)
                delay(PROCESS_RESTART_DELAY_MS)
                connectWithCurrentSettings(config, cleanStateFirst = false)
            }
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleNetworkRetarget("available")
            }

            override fun onLost(network: Network) {
                scheduleNetworkRetarget("lost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scheduleNetworkRetarget("capabilities changed")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                scheduleNetworkRetarget("link properties changed")
            }
        }

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { error ->
            logBuffer.append(LogSource.APP, "Could not watch network changes: ${error.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }
        networkCallback = null
    }

    private fun scheduleNetworkRetarget(reason: String) {
        activeConfig ?: return
        connectionStateHolder.state.value as? ConnectionState.Connected ?: return

        networkReconnectJob?.cancel()
        networkReconnectJob = scope.launch {
            delay(NETWORK_RECONNECT_DELAY_MS)
            val latestConfig = activeConfig ?: return@launch
            val latestState = connectionStateHolder.state.value as? ConnectionState.Connected ?: return@launch
            val currentInterface = withContext(Dispatchers.IO) {
                connectionManager.detectPhysicalInterface(latestState.tunName)
            }

            if (currentInterface.isNullOrBlank()) {
                logBuffer.append(LogSource.APP, "Network changed ($reason), waiting for a usable physical route")
                updateNotification("Waiting for physical route")
                return@launch
            }

            if (currentInterface == latestState.physicalInterface) {
                updateNotification()
                return@launch
            }

            logBuffer.append(
                LogSource.APP,
                "Network changed ($reason): ${latestState.physicalInterface} -> $currentInterface, reconnecting...",
            )
            connectionCommandMutex.withLock {
                updateNotification("Pinning ${latestState.tunName} to $currentInterface")
                stopLogTail()
                connectionManager.disconnect()
                connectWithCurrentSettings(latestConfig, cleanStateFirst = false)
            }
        }
    }

    private fun updateNotification(overrideText: String? = null) {
        val state = connectionStateHolder.state.value
        if (state is ConnectionState.Disconnected) {
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text, showDisconnectAction))
    }

    private fun connectedNotificationText(state: ConnectionState.Connected): String =
        "Pinned ${state.tunName} to ${state.physicalInterface}"

    private fun startAsForeground(title: String, text: String, showDisconnectAction: Boolean) {
        startForeground(NOTIFICATION_ID, buildNotification(title, text, showDisconnectAction))
    }

    private fun buildNotification(title: String, text: String, showDisconnectAction: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, XrayService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "xray_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.material.xray.CONNECT"
        const val ACTION_DISCONNECT = "com.material.xray.DISCONNECT"
        const val ACTION_RELOAD = "com.material.xray.RELOAD"
        const val ACTION_RELOAD_APP_ROUTING = "com.material.xray.RELOAD_APP_ROUTING"
        const val EXTRA_SERVER_CONFIG = "server_config"
        private const val NETWORK_RECONNECT_DELAY_MS = 2_000L
        private const val PROCESS_RESTART_DELAY_MS = 2_000L
        private const val PROCESS_WATCHDOG_INTERVAL_MS = 10_000L
        private const val MAX_XRAY_PROCESS_MEMORY_MB = 512L

        fun connect(context: Context, serverConfig: ServerConfig) {
            val intent = Intent(context, XrayService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_CONFIG, Json.encodeToString(ServerConfig.serializer(), serverConfig))
            }
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(ACTION_DISCONNECT)
            )
        }

        fun reload(context: Context) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(ACTION_RELOAD)
            )
        }

        fun reloadAppRouting(context: Context) {
            context.startService(
                Intent(context, XrayService::class.java).setAction(ACTION_RELOAD_APP_ROUTING)
            )
        }
    }
}
