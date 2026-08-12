package com.material.xray.service

import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

@Singleton
class ConnectionStateCoordinator @Inject constructor() {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    /**
     * One-shot events for the single UI consumer. A conflated channel keeps the latest event
     * emitted while the UI is stopped and delivers it once collection resumes. Conflation also
     * guarantees [emitEvent] never blocks the service's connect path: the emitter may run
     * headless (always-on VPN, boot autostart, tile) with no UI collecting, and every current
     * event is idempotent, so keeping only the most recent one is lossless in effect.
     */
    private val _events = Channel<ConnectionEvent>(Channel.CONFLATED)
    val events: Flow<ConnectionEvent> = _events.receiveAsFlow()
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
     * A root-owned runtime is recorded on disk but cannot be verified from the application process:
     * TPROXY has no interface to observe, and probing a root process needs root, which only the
     * service has. Claiming [ConnectionState.Connected] from the record alone would report a tunnel
     * that may already be dead, and would hide the reconnect the service performs to repair it.
     *
     * So this asserts nothing and moves to a transient state instead, leaving the service to settle
     * it. Returns whether the caller should ask the service to do that; an already live state is left
     * untouched, because only a cold start can observe a record it did not create.
     */
    @Synchronized
    fun markRestoringRecordedRuntime(): Boolean {
        if (_state.value !is ConnectionState.Disconnected) return false
        commit(ConnectionState.Connecting)
        return true
    }

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
        _events.send(event)
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
