package com.material.xray.core.xray

import com.material.xray.model.RootConnectionBackend
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayStateTest {
    @Test
    fun `state written before backend support remains a TUN state`() {
        val state = Json.decodeFromString<XrayState>(
            """{"xrayPid":42,"tunName":"wlan0","routeTable":100}""",
        )

        assertEquals(RootConnectionBackend.Tun, state.rootConnectionBackend)
        assertNull(state.tproxy)
        assertNull(state.appVersionCode)
    }

    @Test
    fun `older TPROXY state defaults tether routing off and LAN bypass on`() {
        val state = Json.decodeFromString<XrayState>(
            """
            {
              "tproxy": {
                "markPrefix": 167772160,
                "markMask": 251658240,
                "routeTable": 300,
                "rulePriority": 11990,
                "outputChainSlot": "a",
                "groups": [{"routeKey": 1,"mark": 167772161,"port": 48321,"inboundTag": "tproxy-in-default"}],
                "ipv6Enabled": false
              }
            }
            """.trimIndent(),
        )

        assertNull(state.tproxy?.tetherUpstreamInterface)
        assertTrue(state.tproxy?.tetherBypassLan == true)
        assertFalse(state.tproxy?.ipv6Enabled == true)
    }
}
