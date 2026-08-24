package com.material.xray.core.nftables

import org.junit.Assert.assertEquals
import org.junit.Test

class NftablesManagerTest {
    @Test
    fun `cleanup tolerates a missing nft binary`() {
        val exitCode = ProcessBuilder(
            "/bin/sh",
            "-c",
            "PATH=/nonexistent; ${nftablesRemovalCommand()}",
        ).start().waitFor()

        assertEquals(0, exitCode)
    }
}
