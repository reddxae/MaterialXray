package com.materialxray.service

import android.content.Context
import com.materialxray.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class PendingRoutingChange {
    APP_ROUTING,
    XRAY_CONFIG,
}

@Singleton
class RoutingChangeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionStateHolder: ConnectionStateHolder,
) {
    private val _pendingChange = MutableStateFlow<PendingRoutingChange?>(null)
    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges

    fun markPendingChanges(kind: PendingRoutingChange = PendingRoutingChange.XRAY_CONFIG) {
        _pendingChange.value = when {
            _pendingChange.value == PendingRoutingChange.XRAY_CONFIG -> PendingRoutingChange.XRAY_CONFIG
            kind == PendingRoutingChange.XRAY_CONFIG -> PendingRoutingChange.XRAY_CONFIG
            else -> PendingRoutingChange.APP_ROUTING
        }
        _hasPendingChanges.value = true
    }

    fun clearPendingChanges() {
        _pendingChange.value = null
        _hasPendingChanges.value = false
    }

    fun maybeReloadActiveConnection() {
        val pendingChange = _pendingChange.value ?: return

        when (connectionStateHolder.state.value) {
            is ConnectionState.Connected -> {
                clearPendingChanges()
                when (pendingChange) {
                    PendingRoutingChange.APP_ROUTING -> XrayService.reloadAppRouting(context)
                    PendingRoutingChange.XRAY_CONFIG -> XrayService.reload(context)
                }
            }
            ConnectionState.Disconnected,
            is ConnectionState.Error -> {
                clearPendingChanges()
            }
            else -> Unit
        }
    }
}
