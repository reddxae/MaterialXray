package com.material.xray.data.parser

import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import com.material.xray.model.SubscriptionMetadata
import com.material.xray.model.SubscriptionUserInfo
import okhttp3.Headers
import okhttp3.Request

data class SubscriptionRequestHeaderValues(
    val userAgent: String,
    val hardwareId: String? = null,
    val deviceOs: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val extraHeaders: List<Pair<String, String>> = emptyList(),
)

object SubscriptionStandardHeaders {
    const val USER_AGENT = "User-Agent"
    const val X_HWID = "x-hwid"
    const val X_DEVICE_OS = "x-device-os"
    const val X_VER_OS = "x-ver-os"
    const val X_DEVICE_MODEL = "x-device-model"

    const val CONTENT_DISPOSITION = "content-disposition"
    const val CONTENT_TYPE = "content-type"
    const val PROFILE_TITLE = "profile-title"
    const val PROFILE_UPDATE_INTERVAL = "profile-update-interval"
    const val SUBSCRIPTION_USERINFO = "subscription-userinfo"
    const val PROFILE_WEB_PAGE_URL = "profile-web-page-url"
    const val ANNOUNCE = "announce"
    const val SUPPORT_URL = "support-url"
    const val FALLBACK_URL = "fallback-url"
    const val PER_APP_PROXY_LIST = "per-app-proxy-list"
    const val PER_APP_PROXY_MODE = "per-app-proxy-mode"
    const val ROUTING = "routing"
    const val ROUTING_ENABLE = "routing-enable"

    val requestHeaderNames: List<String> = listOf(
        USER_AGENT,
        X_HWID,
        X_DEVICE_OS,
        X_VER_OS,
        X_DEVICE_MODEL,
    )

    val responseHeaderNames: List<String> = listOf(
        CONTENT_DISPOSITION,
        CONTENT_TYPE,
        PROFILE_TITLE,
        PROFILE_UPDATE_INTERVAL,
        SUBSCRIPTION_USERINFO,
        PROFILE_WEB_PAGE_URL,
        ANNOUNCE,
        SUPPORT_URL,
        FALLBACK_URL,
        PER_APP_PROXY_LIST,
        PER_APP_PROXY_MODE,
        ROUTING,
        ROUTING_ENABLE,
    )

    fun applyRequestHeaders(
        builder: Request.Builder,
        values: SubscriptionRequestHeaderValues,
    ): Request.Builder = builder.apply {
        header(USER_AGENT, values.userAgent)
        values.hardwareId?.takeIf { it.isNotBlank() }?.let { header(X_HWID, it) }
        values.deviceOs?.takeIf { it.isNotBlank() }?.let { header(X_DEVICE_OS, it) }
        values.osVersion?.takeIf { it.isNotBlank() }?.let { header(X_VER_OS, it) }
        values.deviceModel?.takeIf { it.isNotBlank() }?.let { header(X_DEVICE_MODEL, it) }
        // Applied last so user-provided headers win: a duplicate name (e.g. User-Agent or
        // x-hwid) replaces the value set above. header() keeps only the last value per name.
        values.extraHeaders.forEach { (name, value) ->
            val trimmedName = name.trim()
            if (trimmedName.isNotEmpty()) header(trimmedName, value.trim())
        }
    }

    fun parseMetadata(headers: Headers): SubscriptionMetadata = SubscriptionMetadata(
        contentDisposition = normalizeNullableHeader(headers[CONTENT_DISPOSITION]),
        contentType = normalizeContentType(headers[CONTENT_TYPE]),
        profileTitle = decodeTextHeader(headers[PROFILE_TITLE]),
        profileUpdateIntervalHours = normalizeNullableHeader(headers[PROFILE_UPDATE_INTERVAL])?.toIntOrNull(),
        subscriptionUserInfo = parseSubscriptionUserInfo(headers[SUBSCRIPTION_USERINFO]),
        profileWebPageUrl = normalizeNullableHeader(headers[PROFILE_WEB_PAGE_URL]),
        announce = decodeTextHeader(headers[ANNOUNCE]),
        supportUrl = normalizeNullableHeader(headers[SUPPORT_URL]),
    )

    fun hasKnownResponseHeader(headers: Headers): Boolean = responseHeaderNames.any { headers[it] != null }

    fun parseAppRouting(headers: Headers): SubscriptionAppRouting? {
        val packageNames = headers.values(PER_APP_PROXY_LIST)
            .flatMap { value ->
                normalizeNullableHeader(value)
                    ?.split(PACKAGE_LIST_SEPARATOR_REGEX)
                    .orEmpty()
            }
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
        val mode = SubscriptionAppRoutingMode.fromHeader(headers[PER_APP_PROXY_MODE]) ?: return null
        return SubscriptionAppRouting(packageNames, mode).normalized()
    }

    fun normalizeContentType(value: String?): String? {
        val raw = normalizeNullableHeader(value) ?: return null
        return raw.substringBefore(';').trim().lowercase().ifBlank { null }
    }

    fun isJsonContentType(value: String?): Boolean {
        val normalized = normalizeContentType(value) ?: return false
        return normalized == "application/json" || normalized.endsWith("+json")
    }

    fun isPlainTextContentType(value: String?): Boolean {
        val normalized = normalizeContentType(value) ?: return false
        return normalized.startsWith("text/")
    }

    fun normalizeNullableHeader(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        return trimmed
    }

    fun decodeTextHeader(value: String?): String? {
        val normalized = normalizeNullableHeader(value) ?: return null
        if (!normalized.startsWith(BASE64_PREFIX, ignoreCase = true)) {
            return normalized
        }

        val encoded = normalized.substring(BASE64_PREFIX.length)
        return decodeBase64ToUtf8(encoded) ?: encoded
    }

    fun parseSubscriptionUserInfo(value: String?): SubscriptionUserInfo? {
        val normalized = normalizeNullableHeader(value) ?: return null
        val values = normalized.split(';')
            .mapNotNull { segment ->
                val parts = segment.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                parts[0].trim().lowercase() to parts[1].trim()
            }
            .toMap()

        val info = SubscriptionUserInfo(
            upload = values["upload"]?.toLongOrNull(),
            download = values["download"]?.toLongOrNull(),
            total = values["total"]?.toLongOrNull(),
            expire = values["expire"]?.toLongOrNull(),
        )

        return info.takeIf {
            it.upload != null ||
                it.download != null ||
                it.total != null ||
                it.expire != null
        }
    }

    fun decodeBase64ToUtf8(value: String): String? = decodeLenientBase64ToUtf8(value)

    private const val BASE64_PREFIX = "base64:"
    private val PACKAGE_LIST_SEPARATOR_REGEX = "[,;\\s]+".toRegex()
}
