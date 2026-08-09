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
class ConnectionStateCoordinator @Inject constructor() {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state
    private val _events = MutableSharedFlow<ConnectionEvent>()
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()
    private val _activeBalancerSelection = MutableStateFlow<ActiveBalancerSelection?>(null)
    internal val activeBalancerSelection: StateFlow<ActiveBalancerSelection?> = _activeBalancerSelection.asStateFlow()
    internal val activeBalancerSelectionSubscribers: StateFlow<Int> = _activeBalancerSelection.subscriptionCount

    fun startConnection(transitionState: ConnectionState) {
        require(
            transitionState == ConnectionState.Connecting ||
                transitionState == ConnectionState.ApplyingRoutingChanges,
        ) { "Invalid connection transition state: $transitionState" }
        commit(transitionState)
    }

    fun markUpdatingRoutingData() = commit(ConnectionState.UpdatingRoutingData)

    fun markApplyingRoutingChanges() = commit(ConnectionState.ApplyingRoutingChanges)

    fun markConnected(state: ConnectionState.Connected) = commit(state)

    fun markDisconnecting() = commit(ConnectionState.Disconnecting)

    fun markDisconnected() = commit(ConnectionState.Disconnected)

    fun markError(message: String, retryable: Boolean = true) = commit(ConnectionState.Error(message, retryable))

    fun restoreConnected(state: ConnectionState.Connected) = commit(state)

    /**
     * Clears a state that claims a running tunnel after the runtime backing it has gone away.
     *
     * This coordinator is a process-wide singleton, so a service that stops without completing a
     * disconnect would otherwise leave [state] reporting a connection forever: the UI and the tile
     * would keep showing it, and every disconnect request would wait for a transition that can no
     * longer happen.
     */
    @Synchronized
    fun markRuntimeStopped() {
        if (_state.value.assertsLiveRuntime()) commit(ConnectionState.Disconnected)
    }

    @Synchronized
    fun reconcileDetectedState(detectedState: ConnectionState?): ConnectionState? {
        val currentState = _state.value
        val reconciledState = when {
            detectedState is ConnectionState.InterfaceBusy && !currentState.assertsLiveRuntime() -> detectedState
            detectedState is ConnectionState.Connected && currentState is ConnectionState.Disconnected -> detectedState
            detectedState == null && currentState.canClearDetectedState() -> ConnectionState.Disconnected
            else -> null
        }
        reconciledState?.let(::commit)
        return reconciledState
    }

    @Synchronized
    private fun commit(newState: ConnectionState) {
        _state.value = newState
        if (newState !is ConnectionState.Connected) {
            _activeBalancerSelection.value = null
        }
    }

    @Synchronized
    internal fun updateActiveBalancerSelection(selection: ActiveBalancerSelection?) {
        _activeBalancerSelection.value = selection.takeIf { _state.value is ConnectionState.Connected }
    }

    suspend fun emitEvent(event: ConnectionEvent) {
        _events.emit(event)
    }
}

/**
 * Reports whether this state claims a runtime that is either established or being brought up, and
 * therefore must not be overwritten by an outside observation or outlive the runtime that owns it.
 */
private fun ConnectionState.assertsLiveRuntime(): Boolean = when (this) {
    ConnectionState.Connecting,
    ConnectionState.ApplyingRoutingChanges,
    ConnectionState.UpdatingRoutingData,
    ConnectionState.Disconnecting,
    is ConnectionState.Connected,
    -> true

    ConnectionState.Disconnected,
    is ConnectionState.Error,
    is ConnectionState.InterfaceBusy,
    is ConnectionState.RestartRequired,
    -> false
}

private fun ConnectionState.canClearDetectedState(): Boolean = when (this) {
    is ConnectionState.InterfaceBusy,
    is ConnectionState.RestartRequired,
    is ConnectionState.Connected,
    -> this !is ConnectionState.Connected || corePid <= 0

    ConnectionState.Disconnected,
    ConnectionState.Connecting,
    ConnectionState.ApplyingRoutingChanges,
    ConnectionState.UpdatingRoutingData,
    ConnectionState.Disconnecting,
    is ConnectionState.Error,
    -> false
}

sealed interface ConnectionEvent {
    data object RootUnavailableFallback : ConnectionEvent
}
