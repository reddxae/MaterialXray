package com.material.xray.service

import com.material.xray.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    private fun connectedState(corePid: Int) = ConnectionState.Connected(
        serverName = "Server",
        corePid = corePid,
        tunName = "xray0",
        physicalInterface = "wlan0",
    )
}
