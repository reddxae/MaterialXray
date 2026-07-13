package com.material.xray.service

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class XrayTileService : TileService() {

    @Inject lateinit var settingsRepo: SettingsRepository

    @Inject lateinit var serverRepository: ServerRepository

    @Inject lateinit var connectionStateHolder: ConnectionStateHolder

    @Inject lateinit var alwaysOnVpnState: AlwaysOnVpnState

    @Inject lateinit var routingChangeManager: RoutingChangeManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            combine(
                connectionStateHolder.state,
                settingsRepo.lastServerId,
                alwaysOnVpnState.active,
            ) { state, selectedServerId, alwaysOnVpn ->
                TileSnapshot(state, hasSelectedServer = selectedServerId >= 0, alwaysOnVpn = alwaysOnVpn)
            }.collect { snapshot ->
                updateTile(snapshot)
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (connectionStateHolder.state.value) {
            is ConnectionState.Connected if alwaysOnVpnState.active.value -> Unit
            is ConnectionState.Connected -> {
                XrayService.disconnect(this)
                updateTile(
                    TileSnapshot(
                        state = ConnectionState.Disconnecting,
                        hasSelectedServer = true,
                        alwaysOnVpn = false,
                    ),
                )
            }
            is ConnectionState.Connecting,
            ConnectionState.ApplyingRoutingChanges,
            ConnectionState.UpdatingRoutingData,
            is ConnectionState.Disconnecting,
            -> Unit
            else -> scope.launch { connectSelectedServer() }
        }
    }

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        scope.launch {
            updateTile(
                TileSnapshot(
                    state = connectionStateHolder.state.value,
                    hasSelectedServer = settingsRepo.lastServerId.first() >= 0,
                    alwaysOnVpn = alwaysOnVpnState.active.value,
                ),
            )
        }
    }

    private suspend fun connectSelectedServer() {
        val serverConfig = withContext(Dispatchers.IO) {
            val selectedServerId = settingsRepo.lastServerId.first()
            if (selectedServerId < 0) return@withContext null

            val serverEntity = serverRepository.getById(selectedServerId) ?: return@withContext null
            runCatching { serverRepository.parseConfig(serverEntity) }.getOrNull()
        }
        if (serverConfig == null) {
            updateTile(
                TileSnapshot(
                    state = connectionStateHolder.state.value,
                    hasSelectedServer = false,
                    alwaysOnVpn = alwaysOnVpnState.active.value,
                ),
            )
            return
        }

        routingChangeManager.clearPendingChanges()
        XrayService.connect(this, serverConfig)
        updateTile(
            TileSnapshot(
                state = ConnectionState.Connecting,
                hasSelectedServer = true,
                alwaysOnVpn = alwaysOnVpnState.active.value,
            ),
        )
    }

    private fun updateTile(snapshot: TileSnapshot) {
        qsTile?.run {
            label = snapshot.label()
            icon = Icon.createWithResource(
                applicationContext,
                R.drawable.ic_launcher_material_monochrome,
            )
            state = snapshot.tileState()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = snapshot.stateDescription()
            }
            updateTile()
        }
    }

    private fun TileSnapshot.label(): String {
        val connectedState = state as? ConnectionState.Connected
        return connectedState
            ?.serverName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: localizedString(R.string.app_name)
    }

    private fun TileSnapshot.tileState(): Int = when {
        state is ConnectionState.Connected -> Tile.STATE_ACTIVE
        !hasSelectedServer -> Tile.STATE_UNAVAILABLE
        state.isTransitioning() -> Tile.STATE_UNAVAILABLE
        else -> Tile.STATE_INACTIVE
    }

    private fun TileSnapshot.stateDescription(): String = when {
        state is ConnectionState.Connected && alwaysOnVpn -> localizedString(R.string.tile_state_connected_always_on)
        state is ConnectionState.Connected -> localizedString(R.string.tile_state_connected)
        !hasSelectedServer -> localizedString(R.string.tile_state_no_server_selected)
        state is ConnectionState.Connecting -> localizedString(R.string.tile_state_connecting)
        state is ConnectionState.ApplyingRoutingChanges -> localizedString(R.string.tile_state_applying_routing_changes)
        state is ConnectionState.UpdatingRoutingData -> localizedString(R.string.tile_state_updating_routing_data)
        state is ConnectionState.RestartRequired -> localizedString(R.string.tile_state_restart_required)
        state is ConnectionState.InterfaceBusy -> localizedString(R.string.tile_state_interface_busy)
        state is ConnectionState.Disconnecting -> localizedString(R.string.tile_state_disconnecting)
        state is ConnectionState.Error -> localizedString(R.string.tile_state_connection_error)
        else -> localizedString(R.string.tile_state_disconnected)
    }

    private fun ConnectionState.isTransitioning(): Boolean = this is ConnectionState.Connecting ||
        this is ConnectionState.ApplyingRoutingChanges ||
        this is ConnectionState.UpdatingRoutingData ||
        this is ConnectionState.Disconnecting

    private data class TileSnapshot(
        val state: ConnectionState,
        val hasSelectedServer: Boolean,
        val alwaysOnVpn: Boolean,
    )

    companion object {
        fun requestStateRefresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, XrayTileService::class.java),
                )
            }
        }
    }
}
