package com.material.xray.service

import com.material.xray.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseResetManagerTest {
    @Test
    fun `reset waits for active runtime states`() {
        assertTrue(ConnectionState.Connecting.requiresRuntimeDisconnect())
        assertTrue(ConnectionState.ApplyingRoutingChanges.requiresRuntimeDisconnect())
        assertTrue(
            ConnectionState.Connected(
                serverName = "Server",
                corePid = 42,
                tunName = "xray0",
                physicalInterface = "wlan0",
            ).requiresRuntimeDisconnect(),
        )
        assertTrue(ConnectionState.Disconnecting.requiresRuntimeDisconnect())
    }

    @Test
    fun `reset can proceed from terminal states`() {
        assertFalse(ConnectionState.Disconnected.requiresRuntimeDisconnect())
        assertFalse(ConnectionState.Error("failed").requiresRuntimeDisconnect())
        assertFalse(ConnectionState.InterfaceBusy("xray0").requiresRuntimeDisconnect())
    }
}
