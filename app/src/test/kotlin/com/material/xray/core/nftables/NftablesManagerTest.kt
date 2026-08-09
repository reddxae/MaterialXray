package com.material.xray.core.nftables

import org.junit.Assert.assertEquals
import org.junit.Test

class NftablesManagerTest {
    @Test
    fun `cleanup tolerates a missing nft binary`() {
        assertEquals(0, executeWithoutPath(nftablesRemovalCommand()))
    }

    private fun executeWithoutPath(command: String): Int = ProcessBuilder(
        "/bin/sh",
        "-c",
        "PATH=/nonexistent; $command",
    ).start().waitFor()
}
