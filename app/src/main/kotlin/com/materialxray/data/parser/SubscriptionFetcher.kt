package com.materialxray.data.parser

import android.content.Context
import android.os.Build
import com.materialxray.model.Protocol
import com.materialxray.model.ServerConfig
import com.materialxray.model.SubscriptionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

data class FetchedSubscription(
    val configs: List<ServerConfig>,
    val metadata: SubscriptionMetadata = SubscriptionMetadata(),
    val resolvedUrl: String,
    val permanentRedirectUrl: String? = null,
)

class SubscriptionFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    private val parser = ShareLinkParser()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    suspend fun fetch(url: String): List<ServerConfig> = fetchWithMetadata(url).configs

    suspend fun fetchWithMetadata(url: String): FetchedSubscription = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        val httpUrl = normalizedUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid subscription URL: $normalizedUrl")

        val request = SubscriptionStandardHeaders.applyRequestHeaders(
            builder = Request.Builder()
                .url(httpUrl),
            values = SubscriptionRequestHeaderValues(
                userAgent = buildUserAgent(),
                hardwareId = buildHardwareId(),
                deviceOs = "Android",
                osVersion = buildOsVersion(),
                deviceModel = buildDeviceModel(),
            ),
        ).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Subscription request failed with HTTP ${response.code}")
            }

            val resolvedUrl = response.request.url.toString()
            if (!response.request.url.isHttps) {
                throw IOException("Subscription must be fetched over HTTPS")
            }

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
                permanentRedirectUrl = response.permanentRedirectTarget(originalUrl = normalizedUrl),
            )
        }
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

        val proxyOutbound = findArray("outbounds")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { outbound ->
                outbound.findString("protocol")
                    ?.lowercase()
                    ?.let { it !in SPECIAL_OUTBOUND_PROTOCOLS }
                    ?: true
            }

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
            extra = derived.extra,
            rawConfigJson = canonicalJson,
        )
    }

    private fun deriveOutbound(outbound: JsonObject): DerivedOutbound {
        val protocolName = outbound.findString("protocol")?.lowercase().orEmpty()
        val settings = outbound.findObject("settings")

        return when (protocolName) {
            "vless" -> {
                val vnext = settings?.findArray("vnext")?.firstObject()
                val user = vnext?.findArray("users")?.firstObject()
                val extra = buildMap {
                    user?.findString("encryption")?.takeIf { it.isNotBlank() }?.let { put("encryption", it) }
                    user?.findString("flow")?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                }
                DerivedOutbound(
                    protocol = Protocol.VLESS,
                    address = vnext?.findString("address")
                        ?: outbound.findFirstStringRecursive("address")
                        ?: "",
                    port = vnext?.findInt("port")
                        ?: outbound.findFirstIntRecursive("port")
                        ?: 0,
                    password = user?.findString("id")
                        ?: outbound.findFirstStringRecursive("id")
                        ?: "",
                    extra = extra,
                )
            }

            "vmess" -> {
                val vnext = settings?.findArray("vnext")?.firstObject()
                val user = vnext?.findArray("users")?.firstObject()
                val extra = buildMap {
                    user?.findString("alterId")?.takeIf { it.isNotBlank() }?.let { put("alterId", it) }
                }
                DerivedOutbound(
                    protocol = Protocol.VMESS,
                    address = vnext?.findString("address")
                        ?: outbound.findFirstStringRecursive("address")
                        ?: "",
                    port = vnext?.findInt("port")
                        ?: outbound.findFirstIntRecursive("port")
                        ?: 0,
                    password = user?.findString("id")
                        ?: outbound.findFirstStringRecursive("id")
                        ?: "",
                    extra = extra,
                )
            }

            "trojan" -> {
                val server = settings?.findArray("servers")?.firstObject()
                DerivedOutbound(
                    protocol = Protocol.TROJAN,
                    address = server?.findString("address")
                        ?: outbound.findFirstStringRecursive("address")
                        ?: "",
                    port = server?.findInt("port")
                        ?: outbound.findFirstIntRecursive("port")
                        ?: 0,
                    password = server?.findString("password")
                        ?: outbound.findFirstStringRecursive("password")
                        ?: "",
                    extra = emptyMap(),
                )
            }

            "shadowsocks", "ss" -> {
                val server = settings?.findArray("servers")?.firstObject()
                val extra = buildMap {
                    server?.findString("method")?.takeIf { it.isNotBlank() }?.let { put("method", it) }
                }
                DerivedOutbound(
                    protocol = Protocol.SHADOWSOCKS,
                    address = server?.findString("address")
                        ?: outbound.findFirstStringRecursive("address")
                        ?: "",
                    port = server?.findInt("port")
                        ?: outbound.findFirstIntRecursive("port")
                        ?: 0,
                    password = server?.findString("password")
                        ?: outbound.findFirstStringRecursive("password")
                        ?: "",
                    extra = extra,
                )
            }

            else -> DerivedOutbound(
                protocol = Protocol.RAW,
                address = outbound.findFirstStringRecursive("address").orEmpty(),
                port = outbound.findFirstIntRecursive("port") ?: 0,
                password = outbound.findFirstStringRecursive("id")
                    ?: outbound.findFirstStringRecursive("password")
                    ?: "",
                extra = emptyMap(),
            )
        }
    }

    private fun parseMetadata(response: Response): SubscriptionMetadata =
        SubscriptionStandardHeaders.parseMetadata(response.headers)

    private fun isJsonContentType(contentType: String?): Boolean =
        SubscriptionStandardHeaders.isJsonContentType(contentType)

    private fun isPlainTextContentType(contentType: String?): Boolean =
        SubscriptionStandardHeaders.isPlainTextContentType(contentType)

    private fun decodeBase64ToUtf8(value: String): String? =
        SubscriptionStandardHeaders.decodeBase64ToUtf8(value)

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

    private fun buildUserAgent(): String {
        val version = resolveAppVersion()
        return "MaterialXray/$version (Android ${buildOsVersion()}; ${buildDeviceModel()})"
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
            Class.forName("com.materialxray.BuildConfig")
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

    private fun JsonObject.findElement(name: String): JsonElement? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun JsonObject.findObject(name: String): JsonObject? = findElement(name) as? JsonObject

    private fun JsonObject.findArray(name: String): JsonArray? = findElement(name) as? JsonArray

    private fun JsonObject.findString(name: String): String? =
        (findElement(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.findInt(name: String): Int? = findString(name)?.toIntOrNull()

    private fun JsonArray.firstObject(): JsonObject? = firstOrNull() as? JsonObject

    private fun JsonArray.stringList(): List<String> =
        mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonElement.findFirstStringRecursive(name: String): String? = when (this) {
        is JsonObject -> {
            findString(name)
                ?: values.asSequence().mapNotNull { it.findFirstStringRecursive(name) }.firstOrNull()
        }

        is JsonArray -> asSequence().mapNotNull { it.findFirstStringRecursive(name) }.firstOrNull()
        else -> null
    }

    private fun JsonElement.findFirstIntRecursive(name: String): Int? =
        findFirstStringRecursive(name)?.toIntOrNull()

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
