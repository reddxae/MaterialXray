package com.material.xray.core.xray

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayStatsConfigTest {
    @Test
    fun `filesystem API socket round trips through config`() {
        val endpoint = XrayApiEndpoint.FileSystemUnixSocket("/data/user/0/app/files/api.sock")
        val config = buildStatsApi(endpoint)

        assertEquals(endpoint.path, config.getValue("listen").jsonPrimitive.content)
        assertEquals(endpoint, parseXrayApiEndpoint("""{"api":$config}"""))
    }
}
