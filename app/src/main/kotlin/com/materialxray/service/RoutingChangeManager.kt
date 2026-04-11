package com.materialxray.service

import android.content.Context
import com.materialxray.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutingChangeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionStateHolder: ConnectionStateHolder,
) {
    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges

    fun markPendingChanges() {
        _hasPendingChanges.value = true
    }

    fun clearPendingChanges() {
        _hasPendingChanges.value = false
    }

    fun maybeReloadActiveConnection() {
        if (!_hasPendingChanges.value) return

        when (connectionStateHolder.state.value) {
            is ConnectionState.Connected -> {
                clearPendingChanges()
                XrayService.reload(context)
            }
            ConnectionState.Disconnected,
            is ConnectionState.Error -> {
                clearPendingChanges()
            }
            else -> Unit
        }
    }
}
