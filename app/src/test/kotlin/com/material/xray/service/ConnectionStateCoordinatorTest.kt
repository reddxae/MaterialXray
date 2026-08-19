package com.material.xray.service

import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStateCoordinatorTest {
    @Test
    fun `tunnel reconciliation cannot overwrite an active connection transition`() {
        val coordinator = ConnectionStateCoordinator()
        coordinator.startConnection(ConnectionState.Connecting)

        val result = coordinator.reconcileDetectedState(ConnectionState.InterfaceBusy("xray0"))

        assertNull(result)
        assertEquals(ConnectionState.Connecting, coordinator.state.value)
    }

    @Test
    fun `tunnel reconciliation restores an externally running connection`() {
        val coordinator = ConnectionStateCoordinator()
        val detected = connectedState(corePid = 42)

        val result = coordinator.reconcileDetectedState(detected)

        assertEquals(detected, result)
        assertEquals(detected, coordinator.state.value)
    }

    @Test
    fun `an unverifiable recorded runtime is never reported as connected`() {
        val coordinator = ConnectionStateCoordinator()

        val shouldAskService = coordinator.markRestoringRecordedRuntime()

        assertTrue(shouldAskService)
        assertEquals(ConnectionState.Connecting, coordinator.state.value)
    }

    @Test
    fun `a recorded runtime never disturbs a state this process already owns`() {
        val live = listOf(
            connectedState(corePid = 42),
            ConnectionState.Connecting,
            ConnectionState.Disconnecting,
            ConnectionState.Error("boom"),
        )

        live.forEach { state ->
            val coordinator = ConnectionStateCoordinator()
            when (state) {
                is ConnectionState.Connected -> coordinator.restoreConnected(state)
                ConnectionState.Connecting -> coordinator.startConnection(ConnectionState.Connecting)
                ConnectionState.Disconnecting -> coordinator.markDisconnecting()
                is ConnectionState.Error -> coordinator.markError(state.message)
                else -> error("unreachable")
            }

            assertFalse("state=$state", coordinator.markRestoringRecordedRuntime())
            assertEquals(state, coordinator.state.value)
        }
    }

    @Test
    fun `missing tunnel only clears placeholder connected state`() {
        val coordinator = ConnectionStateCoordinator()
        coordinator.restoreConnected(connectedState(corePid = 0))

        coordinator.reconcileDetectedState(null)

        assertEquals(ConnectionState.Disconnected, coordinator.state.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `connection start rejects terminal states`() {
        ConnectionStateCoordinator().startConnection(ConnectionState.Disconnected)
    }

    @Test
    fun `a stopped runtime clears a connected state that reports a live core`() {
        val coordinator = ConnectionStateCoordinator()
        coordinator.restoreConnected(connectedState(corePid = 42))

        coordinator.markRuntimeStopped()

        assertEquals(ConnectionState.Disconnected, coordinator.state.value)
    }

    @Test
    fun `a stopped runtime clears an in-flight connection attempt`() {
        val coordinator = ConnectionStateCoordinator()
        coordinator.startConnection(ConnectionState.Connecting)

        coordinator.markRuntimeStopped()

        assertEquals(ConnectionState.Disconnected, coordinator.state.value)
    }

    @Test
    fun `a stopped runtime keeps a reported failure visible`() {
        val coordinator = ConnectionStateCoordinator()
        coordinator.markError("boom", retryable = false)

        coordinator.markRuntimeStopped()

        assertEquals(ConnectionState.Error("boom", retryable = false), coordinator.state.value)
    }

    @Test
    fun `connection progress remains visible until its step finishes`() {
        val coordinator = ConnectionStateCoordinator()
        val token = coordinator.beginConnectionProgress(ConnectionProgress.ResolvingEntryServer)
        assertEquals(ConnectionProgress.ResolvingEntryServer, coordinator.connectionProgress.value)

        coordinator.endConnectionProgress(token)
        assertNull(coordinator.connectionProgress.value)
    }

    @Test
    fun `finishing a nested step restores its parent progress`() {
        val coordinator = ConnectionStateCoordinator()
        val older = coordinator.beginConnectionProgress(ConnectionProgress.PreparingRuntime)
        val newer = coordinator.beginConnectionProgress(ConnectionProgress.StoppingCore)

        coordinator.endConnectionProgress(newer)
        assertEquals(ConnectionProgress.PreparingRuntime, coordinator.connectionProgress.value)

        coordinator.endConnectionProgress(older)
        assertNull(coordinator.connectionProgress.value)
    }

    @Test
    fun `balancer subscriber count follows active UI collection`() = runTest {
        val coordinator = ConnectionStateCoordinator()
        assertEquals(0, coordinator.activeBalancerSelectionSubscribers.value)

        val collection = backgroundScope.launch { coordinator.activeBalancerSelection.collect { } }
        runCurrent()
        assertEquals(1, coordinator.activeBalancerSelectionSubscribers.value)

        collection.cancel()
        runCurrent()
        assertEquals(0, coordinator.activeBalancerSelectionSubscribers.value)
    }

    private fun connectedState(corePid: Int) = ConnectionState.Connected(
        serverName = "Server",
        corePid = corePid,
        tunName = "xray0",
        physicalInterface = "wlan0",
    )
}
