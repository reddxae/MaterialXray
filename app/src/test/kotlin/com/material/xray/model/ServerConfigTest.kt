package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {

    @Test
    fun `endpointSummary appends raw for raw json configs`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "tcp"),
            security = ServerConfig.Security(type = "reality"),
            rawConfigJson = "{\"outbounds\":[]}",
        )

        assertEquals("vless • tcp • reality • raw", config.endpointSummary())
    }

    @Test
    fun `endpointSummary omits raw for generated configs`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "ws"),
            security = ServerConfig.Security(type = "tls"),
        )

        assertEquals("vless • ws • tls", config.endpointSummary())
    }
}
