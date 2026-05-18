package com.material.xray.service

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.material.xray.R
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class XrayTileService : TileService() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var connectionStateHolder: ConnectionStateHolder
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
            combine(connectionStateHolder.state, settingsRepo.lastServerId) { state, selectedServerId ->
                TileSnapshot(state, hasSelectedServer = selectedServerId >= 0)
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
            is ConnectionState.Connected -> {
                XrayService.disconnect(this)
                updateTile(TileSnapshot(ConnectionState.Disconnecting, hasSelectedServer = true))
            }
            is ConnectionState.Connecting,
            ConnectionState.ApplyingRoutingChanges,
            ConnectionState.UpdatingRoutingData,
            is ConnectionState.Disconnecting -> Unit
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
                )
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
            updateTile(TileSnapshot(connectionStateHolder.state.value, hasSelectedServer = false))
            return
        }

        routingChangeManager.clearPendingChanges()
        XrayService.connect(this, serverConfig)
        updateTile(TileSnapshot(ConnectionState.Connecting, hasSelectedServer = true))
    }

    private fun updateTile(snapshot: TileSnapshot) {
        qsTile?.run {
            label = getString(R.string.app_name)
            icon = Icon.createWithResource(
                applicationContext,
                R.drawable.ic_launcher_material_monochrome,
            )
            state = snapshot.tileState()
            updateTile()
        }
    }

    private fun TileSnapshot.tileState(): Int =
        when {
            state is ConnectionState.Connected -> Tile.STATE_ACTIVE
            !hasSelectedServer -> Tile.STATE_UNAVAILABLE
            state.isTransitioning() -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }

    private fun ConnectionState.isTransitioning(): Boolean =
        this is ConnectionState.Connecting ||
            this is ConnectionState.ApplyingRoutingChanges ||
            this is ConnectionState.UpdatingRoutingData ||
            this is ConnectionState.Disconnecting

    private data class TileSnapshot(
        val state: ConnectionState,
        val hasSelectedServer: Boolean,
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
