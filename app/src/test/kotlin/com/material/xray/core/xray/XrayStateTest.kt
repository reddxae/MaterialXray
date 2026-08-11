package com.material.xray.core.xray

import com.material.xray.model.RootConnectionBackend
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayStateTest {
    @Test
    fun `state written before backend support remains a TUN state`() {
        val state = Json.decodeFromString<XrayState>(
            """{"xrayPid":42,"tunName":"wlan0","routeTable":100}""",
        )

        assertEquals(RootConnectionBackend.Tun, state.rootConnectionBackend)
        assertNull(state.tproxy)
    }
}
