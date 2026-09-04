package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Test

class XrayRoutingUpdaterTest {
    @Test
    fun `CLI timeout is passed as integer seconds`() {
        assertEquals(
            listOf(
                "/data/app/libxray.so",
                "api",
                "adrules",
                "--server=127.0.0.1:48123",
                "--timeout=2",
                "/data/user/0/com.material.xray/files/routing.json",
            ),
            buildXrayRoutingCommand(
                executable = "/data/app/libxray.so",
                server = "127.0.0.1:48123",
                inputPath = "/data/user/0/com.material.xray/files/routing.json",
            ),
        )
    }
}
