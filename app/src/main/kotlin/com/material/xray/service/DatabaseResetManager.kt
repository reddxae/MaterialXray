package com.material.xray.service

import android.content.Context
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class DatabaseResetManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val activeConfigOverrideStore: ActiveConfigOverrideStore,
) {
    suspend fun reset() {
        if (stateCoordinator.state.value.requiresRuntimeDisconnect()) {
            XrayService.disconnect(context, force = true)
            check(
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                    stateCoordinator.state.first { !it.requiresRuntimeDisconnect() }
                } != null,
            ) { "Timed out waiting for the active connection to stop" }
        }

        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            settingsRepository.setLastServerId(-1)
            activeConfigOverrideStore.clear()
            routingChangeManager.clearPendingChanges()
        }
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

internal fun ConnectionState.requiresRuntimeDisconnect(): Boolean = when (this) {
    ConnectionState.Connecting,
    ConnectionState.ApplyingRoutingChanges,
    ConnectionState.UpdatingRoutingData,
    is ConnectionState.Connected,
    ConnectionState.Disconnecting,
    -> true

    ConnectionState.Disconnected,
    is ConnectionState.Error,
    is ConnectionState.InterfaceBusy,
    is ConnectionState.RestartRequired,
    -> false
}
