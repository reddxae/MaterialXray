package com.material.xray.service

import com.material.xray.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
