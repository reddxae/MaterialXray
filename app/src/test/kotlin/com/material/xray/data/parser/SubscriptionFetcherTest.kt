package com.material.xray.data.parser

import com.material.xray.model.HAPP_USER_AGENT
import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_INSECURE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UP
import com.material.xray.model.SERVER_EXTRA_USERNAME
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_ADDRESS
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_PUBLIC_KEY
import com.material.xray.model.SubscriptionAppRoutingMode
import com.material.xray.model.SubscriptionHeader
import com.material.xray.model.SubscriptionRequestIdentity
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.endpointSummary
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol as OkHttpProtocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFetcherTest {

    @Test
    fun `parse app routing headers normalizes package list`() {
        val headers = Headers.headersOf(
            "per-app-proxy-list",
            "com.example.app, org.example.second; invalid-package",
            "per-app-proxy-mode",
            "proxy",
        )

        val routing = requireNotNull(SubscriptionStandardHeaders.parseAppRouting(headers))

        assertEquals(SubscriptionAppRoutingMode.DefaultSelected, routing.mode)
        assertEquals(listOf("com.example.app", "org.example.second"), routing.packageNames)
    }

    @Test
    fun `parse app routing treats bypass mode as direct`() {
        val headers = Headers.headersOf(
            "per-app-proxy-list",
            "com.example.app",
            "per-app-proxy-mode",
            "bypass",
        )

        val routing = requireNotNull(SubscriptionStandardHeaders.parseAppRouting(headers))

        assertEquals(SubscriptionAppRoutingMode.Direct, routing.mode)
        assertEquals(listOf("com.example.app"), routing.packageNames)
    }

    @Test
    fun `parse app routing reads invert flag from header`() {
        val headers = Headers.headersOf(
            "per-app-proxy-list",
            "com.example.app",
            "per-app-proxy-mode",
            "bypass",
            "per-app-proxy-list-invert",
            "1",
        )

        val routing = requireNotNull(SubscriptionStandardHeaders.parseAppRouting(headers))

        assertEquals(true, routing.inverted)
    }

    @Test
    fun `parse app routing keeps invert off for falsy values`() {
        val headers = Headers.headersOf(
            "per-app-proxy-list",
            "com.example.app",
            "per-app-proxy-mode",
            "bypass",
            "per-app-proxy-list-invert",
            "0",
        )

        val routing = requireNotNull(SubscriptionStandardHeaders.parseAppRouting(headers))

        assertEquals(false, routing.inverted)
    }

    @Test
    fun `parse routing header decodes provider rules`() {
        val payload = """
            {
              "domainStrategy": "IPIfNonMatch",
              "domainMatcher": "hybrid",
              "rules": [
                {
                  "__name__": "Block ads",
                  "id": "block-ads-provider",
                  "type": "field",
                  "domain": ["geosite:category-ads-all"],
                  "outboundTag": "block"
                },
                {
                  "__name__": "DIRECT for RU domains",
                  "id": "ru-domains-provider",
                  "type": "field",
                  "domain": ["domain:ru", "geosite:category-ru"],
                  "outboundTag": "direct"
                },
                {
                  "__name__": "DIRECT for RU IPs",
                  "id": "ru-ips-provider",
                  "type": "field",
                  "ip": ["geoip:ru"],
                  "outboundTag": "direct"
                }
              ]
            }
        """.trimIndent()
        val headers = Headers.headersOf(
            "routing",
            Base64.getEncoder().encodeToString(payload.toByteArray()),
        )

        val routing = requireNotNull(SubscriptionRoutingHeaderParser.parse(headers))

        assertEquals("IPIfNonMatch", routing.domainStrategy)
        assertEquals("hybrid", routing.domainMatcher)
        assertEquals(listOf("Block ads", "DIRECT for RU domains", "DIRECT for RU IPs"), routing.rules.map { it.name })
        assertEquals(listOf("geosite:category-ads-all"), routing.rules[0].domains)
        assertEquals("block", routing.rules[0].outboundTag)
        assertEquals(listOf("geoip:ru"), routing.rules[2].ips)
        assertEquals("direct", routing.rules[2].outboundTag)
        assertNull(routing.fallbackOutboundTag)
    }

    @Test
    fun `parse routing header ignores invalid payload`() {
        val headers = Headers.headersOf("routing", "not-valid-routing")

        assertNull(SubscriptionRoutingHeaderParser.parse(headers))
    }

    @Test
    fun `parse routing header skips unsupported rules instead of broadening them`() {
        val payload = """
            {
              "rules": [
                {"outboundTag": "block", "domain": ["domain:missing-type.example"]},
                {
                  "type": "field",
                  "outboundTag": "direct",
                  "domain": ["domain:network-only.example"],
                  "network": "tcp"
                },
                {
                  "type": "field",
                  "outboundTag": "direct",
                  "domain": ["domain:supported.example"]
                }
              ]
            }
        """.trimIndent()
        val headers = Headers.headersOf(
            "routing",
            Base64.getEncoder().encodeToString(payload.toByteArray()),
        )

        val routing = requireNotNull(SubscriptionRoutingHeaderParser.parse(headers))

        assertEquals(listOf("domain:supported.example"), routing.rules.single().domains)
    }

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
        assertEquals("vless • tls • ws", config.endpointSummary())
    }

    @Test
    fun `fetch labels configs with multiple proxy outbounds as multiconnect`() = runTest {
        val body = """
            {
              "remarks": "Auto",
              "outbounds": [
                { "protocol": "vless", "tag": "proxy-2" },
                {
                  "protocol": "trojan",
                  "tag": "proxy",
                  "settings": { "servers": [{ "address": "proxy.example", "port": 443, "password": "secret" }] }
                },
                { "protocol": "hysteria", "tag": "proxy-3" },
                { "protocol": "freedom", "tag": "direct" },
                { "protocol": "blackhole", "tag": "block" }
              ]
            }
        """.trimIndent()
        val fetcher = fetcherReturning(body, contentType = "application/json")

        val config = fetcher.fetchWithMetadata("https://subscriptions.example/json-auto").configs.single()

        assertEquals(Protocol.TROJAN, config.protocol)
        assertEquals("proxy.example", config.address)
        assertEquals("multi • 3 servers", config.endpointSummary())
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
    fun `fetch reports HTTP status failures`() = runTest {
        val fetcher = fetcherReturning(
            body = "Forbidden",
            contentType = "text/plain",
            statusCode = 403,
        )

        val error = runCatching {
            fetcher.fetchWithMetadata("https://subscriptions.example/forbidden")
        }.exceptionOrNull()

        assertTrue(error is SubscriptionFetchException)
        error as SubscriptionFetchException
        assertEquals(SubscriptionFetchException.Reason.HTTP_STATUS, error.reason)
        assertEquals(403, error.statusCode)
    }

    @Test
    fun `fetch reports empty successful responses`() = runTest {
        val fetcher = fetcherReturning(body = "", contentType = "text/plain")

        val error = runCatching {
            fetcher.fetchWithMetadata("https://subscriptions.example/empty")
        }.exceptionOrNull()

        assertTrue(error is SubscriptionFetchException)
        error as SubscriptionFetchException
        assertEquals(SubscriptionFetchException.Reason.EMPTY_RESPONSE, error.reason)
        assertEquals(200, error.statusCode)
    }

    @Test
    fun `fetch reports responses without supported configurations`() = runTest {
        val fetcher = fetcherReturning(body = "not a subscription", contentType = "text/plain")

        val error = runCatching {
            fetcher.fetchWithMetadata("https://subscriptions.example/unsupported")
        }.exceptionOrNull()

        assertTrue(error is SubscriptionFetchException)
        error as SubscriptionFetchException
        assertEquals(SubscriptionFetchException.Reason.UNSUPPORTED_CONTENT, error.reason)
        assertEquals(200, error.statusCode)
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

    @Test
    fun `fetch derives HTTP SOCKS and WireGuard json outbounds`() = runTest {
        val body = """
            [
              {
                "remarks": "HTTP JSON",
                "outbounds": [{
                  "protocol": "http",
                  "settings": { "address": "http.example", "port": 8080, "user": "alice", "pass": "secret" }
                }]
              },
              {
                "remarks": "SOCKS JSON",
                "outbounds": [{
                  "protocol": "socks",
                  "settings": { "address": "socks.example", "port": 1080 }
                }]
              },
              {
                "remarks": "WireGuard JSON",
                "outbounds": [{
                  "protocol": "wireguard",
                  "settings": {
                    "secretKey": "private-key",
                    "address": ["172.16.0.2/32"],
                    "peers": [{
                      "endpoint": "[2001:db8::1]:51820",
                      "publicKey": "public-key"
                    }]
                  }
                }]
              }
            ]
        """.trimIndent()
        val configs = fetcherReturning(body, contentType = "application/json")
            .fetchWithMetadata("https://subscriptions.example/json-proxies")
            .configs

        assertEquals(listOf(Protocol.HTTP, Protocol.SOCKS, Protocol.WIREGUARD), configs.map { it.protocol })
        assertEquals("alice", configs[0].extra[SERVER_EXTRA_USERNAME])
        assertEquals("secret", configs[0].password)
        assertEquals("socks.example", configs[1].address)
        assertEquals("2001:db8::1", configs[2].address)
        assertEquals(51820, configs[2].port)
        assertEquals("private-key", configs[2].password)
        assertEquals("public-key", configs[2].extra[SERVER_EXTRA_WIREGUARD_PUBLIC_KEY])
        assertEquals("172.16.0.2/32", configs[2].extra[SERVER_EXTRA_WIREGUARD_ADDRESS])
    }

    @Test
    fun `cleartext subscription url is rejected before any request is sent`() = runTest {
        val requests = mutableListOf<Request>()
        val fetcher = requestAwareFetcher(requests) { TestResponse("", "text/plain") }

        val error = runCatching {
            fetcher.fetchWithMetadata("http://subscriptions.example/sub")
        }.exceptionOrNull()

        assertTrue(error is SubscriptionFetchException)
        assertEquals(
            SubscriptionFetchException.Reason.INSECURE_TRANSPORT,
            (error as SubscriptionFetchException).reason,
        )
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `unfollowed redirect to cleartext is reported as insecure transport`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(OkHttpProtocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .headers(Headers.headersOf("location", "http://subscriptions.example/moved"))
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val fetcher = SubscriptionFetcher(client)

        val error = runCatching {
            fetcher.fetchWithMetadata("https://subscriptions.example/sub")
        }.exceptionOrNull()

        assertTrue(error is SubscriptionFetchException)
        assertEquals(
            SubscriptionFetchException.Reason.INSECURE_TRANSPORT,
            (error as SubscriptionFetchException).reason,
        )
    }

    private fun fetcherReturning(
        body: String,
        contentType: String,
        statusCode: Int = 200,
    ): SubscriptionFetcher {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(OkHttpProtocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .headers(Headers.headersOf("content-type", contentType))
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()
        return SubscriptionFetcher(client)
    }

    @Test
    fun `auto identity sends material user agent and device headers`() = runTest {
        val capture = RequestCapture()
        val fetcher = capturingFetcher(capture)

        fetcher.fetchWithMetadata("https://subscriptions.example/auto", SubscriptionRequestIdentity())

        val request = requireNotNull(capture.request)
        assertTrue(request.header("User-Agent").orEmpty().startsWith("Material Xray/"))
        assertEquals("Android", request.header("x-device-os"))
        assertTrue(request.header("x-hwid").orEmpty().isNotBlank())
    }

    @Test
    fun `prefer json fetches json endpoint and preserves query parameters`() = runTest {
        val requests = mutableListOf<Request>()
        val jsonBody = """
            {
              "remarks": "JSON Server",
              "outbounds": [{"protocol":"vless","settings":{"vnext":[{"address":"json.example","port":443,"users":[{"id":"uuid"}]}]}}]
            }
        """.trimIndent()
        val fetcher = requestAwareFetcher(requests) { request ->
            assertEquals("token=value", request.url.encodedQuery)
            TestResponse(jsonBody, "application/json")
        }

        val subscription = fetcher.fetchWithMetadata(
            url = "https://subscriptions.example/base?token=value",
            preferJson = true,
        )

        assertEquals(listOf("/base/json"), requests.map { it.url.encodedPath })
        assertEquals("json.example", subscription.configs.single().address)
    }

    @Test
    fun `prefer json falls back to base url when json endpoint is empty`() = runTest {
        val requests = mutableListOf<Request>()
        val fetcher = requestAwareFetcher(requests) { request ->
            if (request.url.encodedPath.endsWith("/json")) {
                TestResponse("", "application/json")
            } else {
                TestResponse("vless://uuid@base.example:443?encryption=none&type=tcp#Base", "text/plain")
            }
        }

        val subscription = fetcher.fetchWithMetadata(
            url = "https://subscriptions.example/base",
            preferJson = true,
        )

        assertEquals(listOf("/base/json", "/base"), requests.map { it.url.encodedPath })
        assertEquals("base.example", subscription.configs.single().address)
    }

    @Test
    fun `prefer json falls back to base url when json endpoint is broken`() = runTest {
        val requests = mutableListOf<Request>()
        val fetcher = requestAwareFetcher(requests) { request ->
            if (request.url.encodedPath.endsWith("/json")) {
                TestResponse("{broken", "application/json")
            } else {
                TestResponse("vless://uuid@base.example:443?encryption=none&type=tcp#Base", "text/plain")
            }
        }

        val subscription = fetcher.fetchWithMetadata(
            url = "https://subscriptions.example/base",
            preferJson = true,
        )

        assertEquals(listOf("/base/json", "/base"), requests.map { it.url.encodedPath })
        assertEquals("base.example", subscription.configs.single().address)
    }

    @Test
    fun `disabled json preference fetches only saved url`() = runTest {
        val requests = mutableListOf<Request>()
        val fetcher = requestAwareFetcher(requests) {
            TestResponse("vless://uuid@base.example:443?encryption=none&type=tcp#Base", "text/plain")
        }

        fetcher.fetchWithMetadata(
            url = "https://subscriptions.example/base",
            preferJson = false,
        )

        assertEquals(listOf("/base"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `auto identity omits hwid when disabled`() = runTest {
        val capture = RequestCapture()
        val fetcher = capturingFetcher(capture)

        fetcher.fetchWithMetadata(
            "https://subscriptions.example/auto-no-hwid",
            SubscriptionRequestIdentity(sendHardwareId = false),
        )

        assertNull(requireNotNull(capture.request).header("x-hwid"))
    }

    @Test
    fun `happ identity sends happ user agent`() = runTest {
        val capture = RequestCapture()
        val fetcher = capturingFetcher(capture)

        fetcher.fetchWithMetadata(
            "https://subscriptions.example/happ",
            SubscriptionRequestIdentity(mode = SubscriptionUserAgentMode.HAPP),
        )

        val request = requireNotNull(capture.request)
        assertEquals(HAPP_USER_AGENT, request.header("User-Agent"))
        assertEquals("Android", request.header("x-device-os"))
        assertTrue(request.header("x-hwid").orEmpty().isNotBlank())
    }

    @Test
    fun `custom identity sends custom user agent and headers without device headers`() = runTest {
        val capture = RequestCapture()
        val fetcher = capturingFetcher(capture)

        fetcher.fetchWithMetadata(
            "https://subscriptions.example/custom",
            SubscriptionRequestIdentity(
                mode = SubscriptionUserAgentMode.CUSTOM,
                customUserAgent = "MyClient/2.0",
                customHeaders = listOf(SubscriptionHeader("X-Test", "abc")),
            ),
        )

        val request = requireNotNull(capture.request)
        assertEquals("MyClient/2.0", request.header("User-Agent"))
        assertEquals("abc", request.header("X-Test"))
        assertNull(request.header("x-device-os"))
        // HWID toggle is on by default, so it is still appended in custom mode.
        assertTrue(request.header("x-hwid").orEmpty().isNotBlank())
    }

    @Test
    fun `custom identity preserves user provided hwid header`() = runTest {
        val capture = RequestCapture()
        val fetcher = capturingFetcher(capture)

        fetcher.fetchWithMetadata(
            "https://subscriptions.example/custom-hwid",
            SubscriptionRequestIdentity(
                mode = SubscriptionUserAgentMode.CUSTOM,
                customUserAgent = "MyClient/2.0",
                customHeaders = listOf(SubscriptionHeader("x-hwid", "custom-device")),
            ),
        )

        assertEquals("custom-device", requireNotNull(capture.request).header("x-hwid"))
    }

    @Test
    fun `body comment headers extract known keys and ignore unknown lines`() {
        val body = """
            #profile-title: Test Panel
            # profile-update-interval: 12
            #SUBSCRIPTION-USERINFO: upload=1; download=2; total=3; expire=4
            #created-by: some panel
            #no-colon-line
            vless://uuid@example.com:443?encryption=none&type=tcp#Node
        """.trimIndent()

        val headers = SubscriptionBodyComments.parse(body)

        assertEquals("Test Panel", headers["profile-title"])
        assertEquals("12", headers["profile-update-interval"])
        assertEquals("upload=1; download=2; total=3; expire=4", headers["subscription-userinfo"])
        assertNull(headers["created-by"])
        assertEquals(3, headers.size)
    }

    @Test
    fun `body comment headers accept routing key`() {
        val body = """
            #routing: happ://routing/add/eyJCbG9jayI6WyJleGFtcGxlLmNvbSJdfQ==
            vless://uuid@example.com:443?encryption=none&type=tcp#Node
        """.trimIndent()

        val headers = SubscriptionBodyComments.parse(body)

        assertEquals("happ://routing/add/eyJCbG9jayI6WyJleGFtcGxlLmNvbSJdfQ==", headers["routing"])
    }

    @Test
    fun `body comment headers accept routing enable key`() {
        val body = """
            #routing-enable: 0
            vless://uuid@example.com:443?encryption=none&type=tcp#Node
        """.trimIndent()

        val headers = SubscriptionBodyComments.parse(body)

        assertEquals("0", headers["routing-enable"])
    }

    @Test
    fun `merged headers keep response header precedence over body comments`() {
        val responseHeaders = Headers.headersOf("profile-title", "From Header")
        val bodyHeaders = SubscriptionBodyComments.parse("#profile-title: From Body")

        val merged = SubscriptionBodyComments.merge(responseHeaders, bodyHeaders)

        assertEquals("From Header", merged["profile-title"])
    }

    @Test
    fun `merged headers union per app proxy list from both sources`() {
        val responseHeaders = Headers.headersOf("per-app-proxy-list", "com.example.one")
        val bodyHeaders = SubscriptionBodyComments.parse("#per-app-proxy-list: com.example.two")

        val merged = SubscriptionBodyComments.merge(responseHeaders, bodyHeaders)

        assertEquals(listOf("com.example.two", "com.example.one"), merged.values("per-app-proxy-list"))
    }

    @Test
    fun `fetch reads metadata from body comments`() = runTest {
        val fetcher = capturingFetcher(
            capture = RequestCapture(),
            body = """
                #profile-title: Comment Panel
                #subscription-userinfo: upload=11; download=22; total=33; expire=1700000000
                #support-url: https://t.me/example
                vless://uuid@example.com:443?encryption=none&type=tcp#Node
            """.trimIndent(),
        )

        val fetched = fetcher.fetchWithMetadata("https://subscriptions.example/comments")

        assertEquals("Comment Panel", fetched.metadata.profileTitle)
        assertEquals(11L, fetched.metadata.subscriptionUserInfo?.upload)
        assertEquals("https://t.me/example", fetched.metadata.supportUrl)
        assertEquals(1, fetched.configs.size)
    }

    @Test
    fun `fetch reads metadata from comments inside base64 body`() = runTest {
        val body = Base64.getEncoder().encodeToString(
            """
                #profile-title: Encoded Panel
                vless://uuid@example.com:443?encryption=none&type=tcp#Node
            """.trimIndent().toByteArray(),
        )
        val fetcher = capturingFetcher(
            capture = RequestCapture(),
            body = body,
            contentType = "application/octet-stream",
        )

        val fetched = fetcher.fetchWithMetadata("https://subscriptions.example/base64-comments")

        assertEquals("Encoded Panel", fetched.metadata.profileTitle)
        assertEquals(1, fetched.configs.size)
    }

    @Test
    fun `response headers win over body comment metadata`() = runTest {
        val capture = RequestCapture()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capture.request = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(OkHttpProtocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(Headers.headersOf("profile-title", "Header Title"))
                    .body(
                        """
                            #profile-title: Comment Title
                            vless://uuid@example.com:443?encryption=none&type=tcp#Node
                        """.trimIndent().toResponseBody("text/plain".toMediaType()),
                    )
                    .build()
            }
            .build()

        val fetched = SubscriptionFetcher(client).fetchWithMetadata("https://subscriptions.example/precedence")

        assertEquals("Header Title", fetched.metadata.profileTitle)
    }

    private class RequestCapture {
        @Volatile
        var request: Request? = null
    }

    private data class TestResponse(
        val body: String,
        val contentType: String,
    )

    private fun requestAwareFetcher(
        requests: MutableList<Request>,
        responseFor: (Request) -> TestResponse,
    ): SubscriptionFetcher {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val response = responseFor(request)
                Response.Builder()
                    .request(request)
                    .protocol(OkHttpProtocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(Headers.headersOf("content-type", response.contentType))
                    .body(response.body.toResponseBody(response.contentType.toMediaType()))
                    .build()
            }
            .build()
        return SubscriptionFetcher(client)
    }

    private fun capturingFetcher(
        capture: RequestCapture,
        body: String = "vless://uuid@example.com:443?encryption=none&type=tcp#Captured",
        contentType: String = "text/plain",
    ): SubscriptionFetcher {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capture.request = chain.request()
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
