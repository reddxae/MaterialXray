package com.material.xray.data.parser

import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_INSECURE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UP
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol as OkHttpProtocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFetcherTest {

    @Test
    fun `fetch parses json subscription outbound`() = runTest {
        val body = """
            {
              "remarks": "JSON Server",
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "example.com",
                        "port": 443,
                        "users": [
                          { "id": "uuid", "encryption": "none", "flow": "xtls-rprx-vision" }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "ws",
                    "security": "tls",
                    "wsSettings": { "path": "/ws", "headers": { "Host": "edge.example" } },
                    "tlsSettings": { "serverName": "sni.example", "fingerprint": "chrome", "alpn": ["h2"] }
                  }
                }
              ]
            }
        """.trimIndent()
        val fetcher = fetcherReturning(body, contentType = "application/json")

        val subscription = fetcher.fetchWithMetadata("https://subscriptions.example/json")

        val config = subscription.configs.single()
        assertEquals(Protocol.VLESS, config.protocol)
        assertEquals("JSON Server", config.name)
        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("uuid", config.password)
        assertEquals("ws", config.transport.type)
        assertEquals("/ws", config.transport.path)
        assertEquals("edge.example", config.transport.host)
        assertEquals("tls", config.security.type)
        assertEquals("sni.example", config.security.sni)
        assertEquals("chrome", config.security.fingerprint)
        assertEquals(listOf("h2"), config.security.alpn)
        assertEquals("none", config.extra["encryption"])
        assertEquals("xtls-rprx-vision", config.extra["flow"])
        assertTrue(config.rawConfigJson.isNotBlank())
    }

    @Test
    fun `fetch decodes base64 legacy subscription body`() = runTest {
        val link = "vless://uuid@example.com:443?encryption=none&type=tcp#Base64%20Server"
        val encoded = Base64.getEncoder().encodeToString(link.toByteArray())
        val fetcher = fetcherReturning(encoded, contentType = "text/plain")

        val subscription = fetcher.fetchWithMetadata("https://subscriptions.example/base64")

        val config = subscription.configs.single()
        assertEquals(Protocol.VLESS, config.protocol)
        assertEquals("Base64 Server", config.name)
        assertEquals("example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("uuid", config.password)
        assertEquals("none", config.extra["encryption"])
        assertEquals(link, config.rawUri)
    }

    @Test
    fun `fetch parses json Hysteria outbound`() = runTest {
        val body = """
            {
              "remarks": "HY2 JSON",
              "outbounds": [
                {
                  "protocol": "hysteria",
                  "settings": {
                    "version": 2,
                    "address": "hy.example.com",
                    "port": 443
                  },
                  "streamSettings": {
                    "network": "hysteria",
                    "security": "tls",
                    "hysteriaSettings": { "version": 2, "auth": "authSecret" },
                    "tlsSettings": { "serverName": "real.example.com", "allowInsecure": true, "alpn": ["h3"] },
                    "finalmask": {
                      "udp": [
                        { "type": "salamander", "settings": { "password": "obfsSecret" } }
                      ],
                      "quicParams": {
                        "brutalUp": "100 mbps",
                        "udpHop": { "ports": "20000-30000", "interval": 30 }
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val fetcher = fetcherReturning(body, contentType = "application/json")

        val subscription = fetcher.fetchWithMetadata("https://subscriptions.example/json-hy2")

        val config = subscription.configs.single()
        assertEquals(Protocol.HYSTERIA2, config.protocol)
        assertEquals("HY2 JSON", config.name)
        assertEquals("hy.example.com", config.address)
        assertEquals(443, config.port)
        assertEquals("authSecret", config.password)
        assertEquals("hysteria", config.transport.type)
        assertEquals("tls", config.security.type)
        assertEquals("real.example.com", config.security.sni)
        assertEquals(listOf("h3"), config.security.alpn)
        assertEquals("true", config.extra[SERVER_EXTRA_HYSTERIA_INSECURE])
        assertEquals("salamander", config.extra[SERVER_EXTRA_HYSTERIA_OBFS])
        assertEquals("obfsSecret", config.extra[SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD])
        assertEquals("100 mbps", config.extra[SERVER_EXTRA_HYSTERIA_UP])
        assertEquals("20000-30000", config.extra[SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS])
        assertTrue(config.rawConfigJson.isNotBlank())
    }

    private fun fetcherReturning(body: String, contentType: String): SubscriptionFetcher {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(OkHttpProtocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(Headers.headersOf("content-type", contentType))
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()
        return SubscriptionFetcher(client)
    }
}
