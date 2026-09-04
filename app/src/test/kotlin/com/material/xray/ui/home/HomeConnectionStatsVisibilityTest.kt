package com.material.xray.ui.home

import com.material.xray.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConnectionStatsVisibilityTest {
    @Test
    fun `stats remain visible while switching servers`() {
        assertTrue(ConnectionState.ApplyingRoutingChanges.showsConnectionStats())
    }

    @Test
    fun `stats are visible while connected`() {
        assertTrue(
            ConnectionState.Connected(
                serverName = "Server",
                corePid = 1,
                tunName = "tun0",
                physicalInterface = "wlan0",
            ).showsConnectionStats(),
        )
    }

    @Test
    fun `stats are hidden while disconnected`() {
        assertFalse(ConnectionState.Disconnected.showsConnectionStats())
    }
}
