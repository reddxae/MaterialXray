package com.material.xray.service

import com.material.xray.model.ConnectionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state
    private val _events = MutableSharedFlow<ConnectionEvent>()
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    fun update(newState: ConnectionState) {
        _state.value = newState
    }

    suspend fun emitEvent(event: ConnectionEvent) {
        _events.emit(event)
    }
}

sealed interface ConnectionEvent {
    data object RootUnavailableFallback : ConnectionEvent
}
