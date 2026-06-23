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
    XRAY_CONFIG,
}

@Singleton
class RoutingChangeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectionStateHolder: ConnectionStateHolder,
) {
    private val pendingChange = MutableStateFlow<PendingRoutingChange?>(null)
    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges

    fun markPendingChanges(kind: PendingRoutingChange = PendingRoutingChange.XRAY_CONFIG) {
        pendingChange.value = when {
            pendingChange.value == PendingRoutingChange.XRAY_CONFIG -> PendingRoutingChange.XRAY_CONFIG
            kind == PendingRoutingChange.XRAY_CONFIG -> PendingRoutingChange.XRAY_CONFIG
            else -> PendingRoutingChange.APP_ROUTING
        }
        _hasPendingChanges.value = true
    }

    fun clearPendingChanges() {
        pendingChange.value = null
        _hasPendingChanges.value = false
    }

    fun maybeReloadActiveConnection() {
        val change = pendingChange.value ?: return

        when (connectionStateHolder.state.value) {
            is ConnectionState.Connected -> {
                clearPendingChanges()
                when (change) {
                    PendingRoutingChange.APP_ROUTING -> XrayService.reloadAppRouting(context)
                    PendingRoutingChange.XRAY_CONFIG -> XrayService.reload(context)
                }
            }
            ConnectionState.Disconnected,
            is ConnectionState.RestartRequired,
            is ConnectionState.InterfaceBusy,
            is ConnectionState.Error,
            -> {
                clearPendingChanges()
            }
            else -> Unit
        }
    }
}
