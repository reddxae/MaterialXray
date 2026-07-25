package com.material.xray.core.nftables

import org.junit.Assert.assertEquals
import org.junit.Test

class NftablesManagerTest {
    @Test
    fun `cleanup tolerates missing nft when no table was persisted`() {
        assertEquals(0, executeWithoutPath(nftablesRemovalCommand(required = false)))
    }

    @Test
    fun `cleanup reports missing nft when a table was persisted`() {
        assertEquals(1, executeWithoutPath(nftablesRemovalCommand(required = true)))
    }

    private fun executeWithoutPath(command: String): Int = ProcessBuilder(
        "/bin/sh",
        "-c",
        "PATH=/nonexistent; $command",
    ).start().waitFor()
}
