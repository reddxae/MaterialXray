package com.material.xray.service

import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ConnectionStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state
    private val _events = MutableSharedFlow<ConnectionEvent>()
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()
    private val _activeBalancerSelection = MutableStateFlow<ActiveBalancerSelection?>(null)
    internal val activeBalancerSelection: StateFlow<ActiveBalancerSelection?> = _activeBalancerSelection.asStateFlow()

    fun update(newState: ConnectionState) {
        _state.value = newState
        if (newState !is ConnectionState.Connected) {
            _activeBalancerSelection.value = null
        }
    }

    internal fun updateActiveBalancerSelection(selection: ActiveBalancerSelection?) {
        _activeBalancerSelection.value = selection
    }

    suspend fun emitEvent(event: ConnectionEvent) {
        _events.emit(event)
    }
}

sealed interface ConnectionEvent {
    data object RootUnavailableFallback : ConnectionEvent
}
