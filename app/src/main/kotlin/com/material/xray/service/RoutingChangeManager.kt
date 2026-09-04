package com.material.xray.service

import android.content.Context
import com.material.xray.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PendingRoutingChange {
    APP_ROUTING,
    XRAY_ROUTING,
    XRAY_CONFIG,
}

@Singleton
class RoutingChangeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
) {
    private val pendingChanges = PendingRoutingChangeStore()
    val hasPendingChanges: StateFlow<Boolean> = pendingChanges.hasPendingChanges

    fun markPendingChanges(kind: PendingRoutingChange = PendingRoutingChange.XRAY_ROUTING) {
        pendingChanges.mark(kind)
    }

    fun clearPendingChanges() {
        pendingChanges.clear()
    }

    fun requestActiveConnectionUpdate(kind: PendingRoutingChange): Boolean {
        if (connectionStateCoordinator.state.value !is ConnectionState.Connected) return false
        markPendingChanges(kind)
        maybeReloadActiveConnection()
        return true
    }

    fun maybeReloadActiveConnection() {
        when (connectionStateCoordinator.state.value) {
            is ConnectionState.Connected -> {
                val change = pendingChanges.take() ?: return
                when (change) {
                    PendingRoutingChange.APP_ROUTING -> XrayService.reloadAppRouting(context)
                    PendingRoutingChange.XRAY_ROUTING -> XrayService.reloadXrayRouting(context)
                    PendingRoutingChange.XRAY_CONFIG -> XrayService.reload(context)
                }
            }
            ConnectionState.Disconnected,
            is ConnectionState.RestartRequired,
            is ConnectionState.InterfaceBusy,
            is ConnectionState.Error,
            -> {
                pendingChanges.clear()
            }
            else -> Unit
        }
    }
}

internal class PendingRoutingChangeStore {
    private val lock = Any()
    private var pendingChange: PendingRoutingChange? = null
    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges

    fun mark(kind: PendingRoutingChange) = synchronized(lock) {
        pendingChange = combinePendingRoutingChanges(pendingChange, kind)
        _hasPendingChanges.value = true
    }

    fun clear() = synchronized(lock) {
        pendingChange = null
        _hasPendingChanges.value = false
    }

    fun take(): PendingRoutingChange? = synchronized(lock) {
        pendingChange.also {
            pendingChange = null
            _hasPendingChanges.value = false
        }
    }
}

internal fun combinePendingRoutingChanges(
    current: PendingRoutingChange?,
    incoming: PendingRoutingChange,
): PendingRoutingChange = when {
    current == null || current == incoming -> incoming
    current == PendingRoutingChange.XRAY_CONFIG || incoming == PendingRoutingChange.XRAY_CONFIG ->
        PendingRoutingChange.XRAY_CONFIG
    else -> PendingRoutingChange.XRAY_CONFIG
}
