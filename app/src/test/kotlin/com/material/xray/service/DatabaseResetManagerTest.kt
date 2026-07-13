package com.material.xray.service

import com.material.xray.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseResetManagerTest {
    @Test
    fun `reset waits for active runtime states`() {
        assertTrue(ConnectionState.Connecting.requiresRuntimeDisconnectForReset())
        assertTrue(ConnectionState.ApplyingRoutingChanges.requiresRuntimeDisconnectForReset())
        assertTrue(
            ConnectionState.Connected(
                serverName = "Server",
                corePid = 42,
                tunName = "xray0",
                physicalInterface = "wlan0",
            ).requiresRuntimeDisconnectForReset(),
        )
        assertTrue(ConnectionState.Disconnecting.requiresRuntimeDisconnectForReset())
    }

    @Test
    fun `reset can proceed from terminal states`() {
        assertFalse(ConnectionState.Disconnected.requiresRuntimeDisconnectForReset())
        assertFalse(ConnectionState.Error("failed").requiresRuntimeDisconnectForReset())
        assertFalse(ConnectionState.InterfaceBusy("xray0").requiresRuntimeDisconnectForReset())
    }
}
