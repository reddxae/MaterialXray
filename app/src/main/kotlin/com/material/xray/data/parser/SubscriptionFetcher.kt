package com.material.xray.data.parser

import android.content.Context
import android.os.Build
import com.material.xray.model.HAPP_USER_AGENT
import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_CONGESTION
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_DOWN
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_INSECURE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PACKET_SIZE
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_PIN_SHA256
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_UP
import com.material.xray.model.SERVER_EXTRA_PROXY_OUTBOUND_COUNT
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionMetadata
import com.material.xray.model.SubscriptionRequestIdentity
import com.material.xray.model.SubscriptionUserAgentMode
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class FetchedSubscription(
    val configs: List<ServerConfig>,
    val metadata: SubscriptionMetadata = SubscriptionMetadata(),
    val resolvedUrl: String,
    val permanentRedirectUrl: String? = null,
    val appRouting: SubscriptionAppRouting? = null,
)

class SubscriptionFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    private val parser = ShareLinkParser()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    suspend fun fetch(url: String, preferJson: Boolean = false): List<ServerConfig> = fetchWithMetadata(url, preferJson = preferJson).configs

    suspend fun fetchWithMetadata(
        url: String,
        identity: SubscriptionRequestIdentity = SubscriptionRequestIdentity(),
        preferJson: Boolean = false,
    ): FetchedSubscription = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        val httpUrl = normalizedUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid subscription URL: $normalizedUrl")

        if (preferJson) {
            httpUrl.jsonEndpointOrNull()?.let { jsonUrl ->
                val jsonSubscription = try {
                    fetchUrl(jsonUrl, identity, originalUrl = jsonUrl.toString())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (jsonSubscription != null && jsonSubscription.configs.isNotEmpty()) {
                    return@withContext jsonSubscription.copy(permanentRedirectUrl = null)
                }
            }
        }

        fetchUrl(httpUrl, identity, originalUrl = normalizedUrl)
    }

    private fun fetchUrl(
        httpUrl: HttpUrl,
        identity: SubscriptionRequestIdentity,
        originalUrl: String,
    ): FetchedSubscription {
        val request = SubscriptionStandardHeaders.applyRequestHeaders(
            builder = Request.Builder()
                .url(httpUrl),
            values = buildHeaderValues(identity),
        ).build()

        return client.newCall(request).execute().use { response ->
            val responseError = when {
                !response.isSuccessful -> "Subscription request failed with HTTP ${response.code}"
                !response.request.url.isHttps -> "Subscription must be fetched over HTTPS"
                else -> null
            }
            if (responseError != null) throw IOException(responseError)

            val resolvedUrl = response.request.url.toString()
            val bodyText = response.body.string()

            val metadata = parseMetadata(response)
            val configs = parseSubscriptionBody(
                body = bodyText,
                contentType = metadata.contentType,
            )

            if (configs.isEmpty() && bodyText.isNotBlank()) {
                throw IOException("Subscription did not contain any supported configurations")
            }

            FetchedSubscription(
                configs = configs,
                metadata = metadata,
                resolvedUrl = resolvedUrl,
                permanentRedirectUrl = response.permanentRedirectTarget(originalUrl = originalUrl),
                appRouting = parseAppRouting(response),
            )
        }
    }

    private fun HttpUrl.jsonEndpointOrNull(): HttpUrl? {
        if (pathSegments.lastOrNull().equals("json", ignoreCase = true)) return null
        return newBuilder().addPathSegment("json").build()
    }

    private fun parseSubscriptionBody(body: String, contentType: String?): List<ServerConfig> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        return when {
            isJsonContentType(contentType) -> {
                parseJsonSubscription(trimmed).ifEmpty { parseLegacySubscription(trimmed) }
            }

            isPlainTextContentType(contentType) -> parseLegacySubscription(trimmed)
            else -> parseBestEffortSubscription(trimmed)
        }
    }

    private fun parseBestEffortSubscription(body: String): List<ServerConfig> {
        parseJsonSubscription(body).takeIf { it.isNotEmpty() }?.let { return it }
        parseLegacySubscription(body).takeIf { it.isNotEmpty() }?.let { return it }

        val decoded = decodeBase64ToUtf8(body) ?: return emptyList()
        parseJsonSubscription(decoded).takeIf { it.isNotEmpty() }?.let { return it }
        return parser.parseMultiple(decoded)
    }

    private fun parseLegacySubscription(body: String): List<ServerConfig> {
        val direct = parser.parseMultiple(body)
        val decoded = decodeBase64ToUtf8(body)
        val decodedConfigs = decoded?.let(parser::parseMultiple).orEmpty()
        return if (decodedConfigs.size > direct.size) decodedConfigs else direct
    }

    private fun parseJsonSubscription(body: String): List<ServerConfig> = runCatching {
        val root = json.parseToJsonElement(body)
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> JsonArray(emptyList())
        }

        items.mapIndexedNotNull { index, item ->
            (item as? JsonObject)?.toServerConfig(index)
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.toServerConfig(index: Int): ServerConfig {
        val canonicalJson = json.encodeToString(JsonObject.serializer(), this)
        val remarks = findString("remarks")
            ?: findString("remark")
            ?: findString("name")

        val proxyOutbounds = findArray("outbounds")
            ?.mapNotNull { it as? JsonObject }
            ?.filter { outbound ->
                outbound.findString("protocol")
                    ?.lowercase()
                    ?.let { it !in SPECIAL_OUTBOUND_PROTOCOLS }
                    ?: true
            }
            .orEmpty()
        val proxyOutbound = proxyOutbounds.firstOrNull { outbound ->
            outbound.findString("tag").equals("proxy", ignoreCase = true)
        } ?: proxyOutbounds.firstOrNull()

        val derived = proxyOutbound?.let(::deriveOutbound)
            ?: DerivedOutbound(
                protocol = Protocol.RAW,
                address = findFirstStringRecursive("address").orEmpty(),
                port = findFirstIntRecursive("port") ?: 0,
                password = findFirstStringRecursive("id")
                    ?: findFirstStringRecursive("password")
                    ?: "",
                extra = emptyMap(),
            )

        val streamSettings = proxyOutbound?.findObject("streamSettings")
        val securityType = streamSettings?.findString("security")?.ifBlank { null } ?: "none"

        val transport = ServerConfig.Transport(
            type = streamSettings?.findString("network")?.ifBlank { null } ?: "tcp",
            path = parseTransportPath(streamSettings).orEmpty(),
            host = parseTransportHost(streamSettings).orEmpty(),
            serviceName = streamSettings
                ?.findObject("grpcSettings")
                ?.findString("serviceName")
                .orEmpty(),
            mode = streamSettings
                ?.findObject("xhttpSettings")
                ?.findString("mode")
                .orEmpty(),
        )

        val security = ServerConfig.Security(
            type = securityType,
            sni = parseSecurityServerName(streamSettings).orEmpty(),
            fingerprint = parseSecurityFingerprint(streamSettings).orEmpty(),
            alpn = parseSecurityAlpn(streamSettings),
            publicKey = streamSettings
                ?.findObject("realitySettings")
                ?.findString("publicKey")
                .orEmpty(),
            shortId = streamSettings
                ?.findObject("realitySettings")
                ?.findString("shortId")
                .orEmpty(),
        )

        val resolvedName = remarks
            ?.takeIf { !it.equals("null", ignoreCase = true) && it.isNotBlank() }
            ?: proxyOutbound?.findString("tag")?.takeIf { it.isNotBlank() }
            ?: derived.address.takeIf { it.isNotBlank() }?.let { address ->
                if (derived.port > 0) "$address:${derived.port}" else address
            }
            ?: "JSON Config ${index + 1}"

        return ServerConfig(
            protocol = derived.protocol,
            name = resolvedName,
            address = derived.address,
            port = derived.port,
            password = derived.password,
            transport = transport,
            security = security,
            extra = derived.extra + (SERVER_EXTRA_PROXY_OUTBOUND_COUNT to proxyOutbounds.size.toString()),
            rawConfigJson = canonicalJson,
        )
    }

    private fun deriveOutbound(outbound: JsonObject): DerivedOutbound {
        val protocolName = outbound.findString("protocol")?.lowercase().orEmpty()
        val settings = outbound.findObject("settings")

        return when (protocolName) {
            "vless" -> deriveVlessOutbound(outbound, settings)
            "vmess" -> deriveVmessOutbound(outbound, settings)
            "trojan" -> deriveServerOutbound(outbound, settings, Protocol.TROJAN, passwordKey = "password")
            "shadowsocks", "ss" -> deriveShadowsocksOutbound(outbound, settings)
            "hysteria" -> deriveHysteriaOutbound(outbound, settings)
            else -> deriveRawOutbound(outbound)
        }
    }

    private fun deriveVlessOutbound(outbound: JsonObject, settings: JsonObject?): DerivedOutbound {
        val vnext = settings?.findArray("vnext")?.firstObject()
        val user = vnext?.findArray("users")?.firstObject()
        return DerivedOutbound(
            protocol = Protocol.VLESS,
            address = firstString(vnext, outbound, "address"),
            port = firstInt(vnext, outbound, "port"),
            password = firstString(user, outbound, "id"),
            extra = buildMap {
                user?.findString("encryption")?.takeIf { it.isNotBlank() }?.let { put("encryption", it) }
                user?.findString("flow")?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            },
        )
    }

    private fun deriveVmessOutbound(outbound: JsonObject, settings: JsonObject?): DerivedOutbound {
        val vnext = settings?.findArray("vnext")?.firstObject()
        val user = vnext?.findArray("users")?.firstObject()
        return DerivedOutbound(
            protocol = Protocol.VMESS,
            address = firstString(vnext, outbound, "address"),
            port = firstInt(vnext, outbound, "port"),
            password = firstString(user, outbound, "id"),
            extra = buildMap {
                user?.findString("alterId")?.takeIf { it.isNotBlank() }?.let { put("alterId", it) }
            },
        )
    }

    private fun deriveShadowsocksOutbound(outbound: JsonObject, settings: JsonObject?): DerivedOutbound {
        val server = settings?.findArray("servers")?.firstObject()
        return deriveServerOutbound(
            outbound = outbound,
            settings = settings,
            protocol = Protocol.SHADOWSOCKS,
            passwordKey = "password",
            extra = buildMap {
                server?.findString("method")?.takeIf { it.isNotBlank() }?.let { put("method", it) }
            },
        )
    }

    private fun deriveHysteriaOutbound(outbound: JsonObject, settings: JsonObject?): DerivedOutbound {
        val streamSettings = outbound.findObject("streamSettings")
        val hysteriaSettings = streamSettings?.findObject("hysteriaSettings")
        val tlsSettings = streamSettings?.findObject("tlsSettings")
        val finalMask = streamSettings?.findObject("finalmask")
        val quicParams = finalMask?.findObject("quicParams")
        val udpHop = quicParams?.findObject("udpHop") ?: hysteriaSettings?.findObject("udphop")
        val udpMask = finalMask?.findArray("udp")?.firstObject()
            ?: streamSettings?.findArray("udpmasks")?.firstObject()
        val udpMaskSettings = udpMask?.findObject("settings")

        return DerivedOutbound(
            protocol = Protocol.HYSTERIA2,
            address = firstString(settings, outbound, "address"),
            port = firstInt(settings, outbound, "port"),
            password = firstString(hysteriaSettings, outbound, "auth"),
            extra = buildMap {
                tlsSettings?.findString("allowInsecure")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_INSECURE, it) }
                tlsSettings?.findString("pinnedPeerCertSha256")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_PIN_SHA256, it) }
                udpMask?.findString("type")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_OBFS, it) }
                udpMaskSettings?.findString("password")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD, it) }
                udpMaskSettings?.findString("packetSize")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_OBFS_PACKET_SIZE, it) }
                firstString(quicParams, hysteriaSettings, "brutalUp", "up")?.let { put(SERVER_EXTRA_HYSTERIA_UP, it) }
                firstString(quicParams, hysteriaSettings, "brutalDown", "down")?.let { put(SERVER_EXTRA_HYSTERIA_DOWN, it) }
                firstString(udpHop, hysteriaSettings, "ports", "port")?.let { put(SERVER_EXTRA_HYSTERIA_UDP_HOP_PORTS, it) }
                udpHop?.findString("interval")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_UDP_HOP_INTERVAL, it) }
                hysteriaSettings?.findString("udpIdleTimeout")?.takeIf { it.isNotBlank() }?.let { put(SERVER_EXTRA_HYSTERIA_UDP_IDLE_TIMEOUT, it) }
                firstString(quicParams, hysteriaSettings, "congestion")?.let { put(SERVER_EXTRA_HYSTERIA_CONGESTION, it) }
            },
        )
    }

    private fun deriveServerOutbound(
        outbound: JsonObject,
        settings: JsonObject?,
        protocol: Protocol,
        passwordKey: String,
        extra: Map<String, String> = emptyMap(),
    ): DerivedOutbound {
        val server = settings?.findArray("servers")?.firstObject()
        return DerivedOutbound(
            protocol = protocol,
            address = firstString(server, outbound, "address"),
            port = firstInt(server, outbound, "port"),
            password = firstString(server, outbound, passwordKey),
            extra = extra,
        )
    }

    private fun deriveRawOutbound(outbound: JsonObject): DerivedOutbound = DerivedOutbound(
        protocol = Protocol.RAW,
        address = outbound.findFirstStringRecursive("address").orEmpty(),
        port = outbound.findFirstIntRecursive("port") ?: 0,
        password = outbound.findFirstStringRecursive("id")
            ?: outbound.findFirstStringRecursive("password")
            ?: "",
        extra = emptyMap(),
    )

    private fun firstString(primary: JsonObject?, fallback: JsonObject, key: String): String = primary?.findString(key) ?: fallback.findFirstStringRecursive(key) ?: ""

    private fun firstString(primary: JsonObject?, fallback: JsonObject?, vararg keys: String): String? {
        for (key in keys) {
            primary?.findString(key)?.takeIf { it.isNotBlank() }?.let { return it }
            fallback?.findString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun firstInt(primary: JsonObject?, fallback: JsonObject, key: String): Int = primary?.findInt(key) ?: fallback.findFirstIntRecursive(key) ?: 0

    private fun parseMetadata(response: Response): SubscriptionMetadata = SubscriptionStandardHeaders.parseMetadata(response.headers)

    private fun parseAppRouting(response: Response): SubscriptionAppRouting? = SubscriptionStandardHeaders.parseAppRouting(response.headers)

    private fun isJsonContentType(contentType: String?): Boolean = SubscriptionStandardHeaders.isJsonContentType(contentType)

    private fun isPlainTextContentType(contentType: String?): Boolean = SubscriptionStandardHeaders.isPlainTextContentType(contentType)

    private fun decodeBase64ToUtf8(value: String): String? = SubscriptionStandardHeaders.decodeBase64ToUtf8(value)

    private fun Response.permanentRedirectTarget(originalUrl: String): String? {
        val finalUrl = request.url.toString()
        if (finalUrl == originalUrl) return null

        var current: Response? = priorResponse
        while (current != null) {
            if (current.code == 301 || current.code == 308) {
                return finalUrl
            }
            current = current.priorResponse
        }

        return null
    }

    private fun buildHeaderValues(identity: SubscriptionRequestIdentity): SubscriptionRequestHeaderValues = when (identity.mode) {
        SubscriptionUserAgentMode.AUTO -> deviceHeaderValues(identity, userAgent = buildUserAgent())
        SubscriptionUserAgentMode.HAPP -> deviceHeaderValues(identity, userAgent = HAPP_USER_AGENT)
        SubscriptionUserAgentMode.CUSTOM -> customHeaderValues(identity)
    }

    private fun deviceHeaderValues(
        identity: SubscriptionRequestIdentity,
        userAgent: String,
    ): SubscriptionRequestHeaderValues = SubscriptionRequestHeaderValues(
        userAgent = userAgent,
        hardwareId = if (identity.sendHardwareId) buildHardwareId() else null,
        deviceOs = "Android",
        osVersion = buildOsVersion(),
        deviceModel = buildDeviceModel(),
    )

    private fun customHeaderValues(identity: SubscriptionRequestIdentity): SubscriptionRequestHeaderValues {
        val headers = identity.customHeaders
        val hasHardwareIdHeader = headers.any {
            it.name.trim().equals(SubscriptionStandardHeaders.X_HWID, ignoreCase = true)
        }
        return SubscriptionRequestHeaderValues(
            userAgent = identity.customUserAgent.trim().ifBlank { buildUserAgent() },
            hardwareId = if (identity.sendHardwareId && !hasHardwareIdHeader) buildHardwareId() else null,
            extraHeaders = headers.map { it.name to it.value },
        )
    }

    private fun buildUserAgent(): String {
        val version = resolveAppVersion()
        return "Material Xray/$version (Android ${buildOsVersion()}; ${buildDeviceModel()})"
    }

    private fun buildHardwareId(): String {
        resolveAndroidId()
            ?.takeIf { it.isNotBlank() && !it.equals("9774d56d682e549c", ignoreCase = true) }
            ?.let { return it }

        val seed = listOf(
            Build.BRAND,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.BOARD,
            Build.FINGERPRINT,
        ).joinToString("|")
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun resolveAppVersion(): String {
        val appContext = resolveApplicationContext()
        val packageVersion = appContext?.let { context ->
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        if (!packageVersion.isNullOrBlank()) return packageVersion

        val buildConfigVersion = runCatching {
            Class.forName("com.material.xray.BuildConfig")
                .getField("VERSION_NAME")
                .get(null) as? String
        }.getOrNull()

        return buildConfigVersion?.takeIf { it.isNotBlank() } ?: "dev"
    }

    private fun resolveAndroidId(): String? {
        val appContext = resolveApplicationContext() ?: return null
        return runCatching {
            android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()?.trim()
    }

    private fun resolveApplicationContext(): Context? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        activityThreadClass.getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private fun buildOsVersion(): String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    private fun buildDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { Build.DEVICE.orEmpty().ifBlank { "Android" } }
    }

    private fun parseTransportPath(streamSettings: JsonObject?): String? {
        val wsSettings = streamSettings?.findObject("wsSettings")
        val xhttpSettings = streamSettings?.findObject("xhttpSettings")
        val httpUpgradeSettings = streamSettings?.findObject("httpupgradeSettings")
        return wsSettings?.findString("path")
            ?: xhttpSettings?.findString("path")
            ?: httpUpgradeSettings?.findString("path")
    }

    private fun parseTransportHost(streamSettings: JsonObject?): String? {
        val wsHeaders = streamSettings
            ?.findObject("wsSettings")
            ?.findObject("headers")
        val xhttpSettings = streamSettings?.findObject("xhttpSettings")
        val httpUpgradeSettings = streamSettings?.findObject("httpupgradeSettings")

        return wsHeaders?.findString("Host")
            ?: wsHeaders?.findString("host")
            ?: xhttpSettings?.findString("host")
            ?: httpUpgradeSettings?.findString("host")
    }

    private fun parseSecurityServerName(streamSettings: JsonObject?): String? {
        val tlsSettings = streamSettings?.findObject("tlsSettings")
        val realitySettings = streamSettings?.findObject("realitySettings")
        return tlsSettings?.findString("serverName")
            ?: realitySettings?.findString("serverName")
    }

    private fun parseSecurityFingerprint(streamSettings: JsonObject?): String? {
        val tlsSettings = streamSettings?.findObject("tlsSettings")
        val realitySettings = streamSettings?.findObject("realitySettings")
        return tlsSettings?.findString("fingerprint")
            ?: realitySettings?.findString("fingerprint")
    }

    private fun parseSecurityAlpn(streamSettings: JsonObject?): List<String> {
        val tlsSettings = streamSettings?.findObject("tlsSettings")
        val realitySettings = streamSettings?.findObject("realitySettings")
        return tlsSettings?.findArray("alpn")?.stringList()
            ?: realitySettings?.findArray("alpn")?.stringList()
            ?: emptyList()
    }

    private fun JsonObject.findElement(name: String): JsonElement? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun JsonObject.findObject(name: String): JsonObject? = findElement(name) as? JsonObject

    private fun JsonObject.findArray(name: String): JsonArray? = findElement(name) as? JsonArray

    private fun JsonObject.findString(name: String): String? = (findElement(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.findInt(name: String): Int? = findString(name)?.toIntOrNull()

    private fun JsonArray.firstObject(): JsonObject? = firstOrNull() as? JsonObject

    private fun JsonArray.stringList(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonElement.findFirstStringRecursive(name: String): String? = when (this) {
        is JsonObject -> {
            findString(name)
                ?: values.asSequence().mapNotNull { it.findFirstStringRecursive(name) }.firstOrNull()
        }

        is JsonArray -> asSequence().mapNotNull { it.findFirstStringRecursive(name) }.firstOrNull()
        else -> null
    }

    private fun JsonElement.findFirstIntRecursive(name: String): Int? = findFirstStringRecursive(name)?.toIntOrNull()

    private data class DerivedOutbound(
        val protocol: Protocol,
        val address: String,
        val port: Int,
        val password: String,
        val extra: Map<String, String>,
    )

    private companion object {
        private val SPECIAL_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns")
    }
}
