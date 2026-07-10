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

        assertEquals("vless • reality • raw • raw", config.endpointSummary())
    }

    @Test
    fun `endpointSummary detects multiple proxy outbounds in persisted raw configs`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Auto",
            address = "example.com",
            port = 443,
            password = "id",
            rawConfigJson = """
                {
                  "outbounds": [
                    { "protocol": "vless" },
                    { "protocol": "trojan" },
                    { "protocol": "hysteria" },
                    { "protocol": "freedom" },
                    { "protocol": "blackhole" }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("multiconnect • 3 outbounds", config.endpointSummary())
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

        assertEquals("vless • tls • ws", config.endpointSummary())
    }

    @Test
    fun `endpointSummary prefers transport security for encrypted VLESS`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "xhttp"),
            security = ServerConfig.Security(type = "reality"),
            extra = mapOf("encryption" to "mlkem768x25519plus.random.1rtt.key"),
        )

        assertEquals("vlessenc • reality • xhttp", config.endpointSummary())
    }

    @Test
    fun `endpointSummary uses encryption method when encrypted VLESS has no transport security`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "xhttp"),
            security = ServerConfig.Security(type = "none"),
            extra = mapOf("encryption" to "mlkem768x25519plus.random.1rtt.key"),
        )

        assertEquals("vlessenc • random • xhttp", config.endpointSummary())
    }

    @Test
    fun `endpointSummary labels encrypted VLESS with xorpub method`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "tcp"),
            security = ServerConfig.Security(type = "none"),
            extra = mapOf("encryption" to "mlkem768x25519plus.xorpub.1rtt.key"),
        )

        assertEquals("vlessenc • xorpub • raw", config.endpointSummary())
    }

    @Test
    fun `endpointSummary falls back to native for legacy VLESS encryption values`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "tcp"),
            security = ServerConfig.Security(type = "none"),
            extra = mapOf("encryption" to "mlkem768x25519plus"),
        )

        assertEquals("vlessenc • native • raw", config.endpointSummary())
    }

    @Test
    fun `endpointSummary keeps VLESS label for none encryption`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "tcp"),
            security = ServerConfig.Security(type = "reality"),
            extra = mapOf("encryption" to "none"),
        )

        assertEquals("vless • reality • raw", config.endpointSummary())
    }

    @Test
    fun `endpointSummary combines PQ algorithm with outer security`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "xhttp"),
            security = ServerConfig.Security(type = "reality"),
            extra = mapOf("encryption" to "none", SERVER_EXTRA_MLDSA65_VERIFY to "verifyKey"),
        )

        assertEquals("vless • ml-dsa+reality • xhttp", config.endpointSummary())
    }

    @Test
    fun `endpointSummary hides none security`() {
        val config = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Node",
            address = "example.com",
            port = 443,
            password = "id",
            transport = ServerConfig.Transport(type = "tcp"),
            security = ServerConfig.Security(type = "none"),
        )

        assertEquals("vless • raw", config.endpointSummary())
    }

    @Test
    fun `formatProxyConfigSummary hides inner encryption when outer security is present`() {
        val summary = formatProxyConfigSummary(
            ProxyConfigDisplay(
                protocol = "vlessenc",
                innerEncryption = "random",
                security = "reality",
                transport = "xhttp",
            ),
        )

        assertEquals("vlessenc • reality • xhttp", summary)
    }

    @Test
    fun `formatProxyConfigSummary falls back to inner encryption without outer security`() {
        val summary = formatProxyConfigSummary(
            ProxyConfigDisplay(
                protocol = "vlessenc",
                innerEncryption = "random",
                security = "none",
                transport = "xhttp",
            ),
        )

        assertEquals("vlessenc • random • xhttp", summary)
    }

    @Test
    fun `formatProxyConfigSummary combines PQ algorithm and outer security`() {
        val summary = formatProxyConfigSummary(
            ProxyConfigDisplay(
                protocol = "vless",
                innerEncryption = "none",
                security = "reality",
                pqAlgorithm = "ml-dsa",
                transport = "xhttp",
            ),
        )

        assertEquals("vless • ml-dsa+reality • xhttp", summary)
    }

    @Test
    fun `formatProxyConfigSummary combines PQ security and hides native inner encryption`() {
        val summary = formatProxyConfigSummary(
            ProxyConfigDisplay(
                protocol = "vlessenc",
                innerEncryption = "native",
                security = "reality",
                pqAlgorithm = "ml-dsa",
                transport = "grpc",
            ),
        )

        assertEquals("vlessenc • ml-dsa+reality • grpc", summary)
    }
}
