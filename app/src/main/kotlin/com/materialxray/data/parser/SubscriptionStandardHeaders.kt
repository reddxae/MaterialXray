package com.materialxray.data.parser

import com.materialxray.model.SubscriptionMetadata
import com.materialxray.model.SubscriptionUserInfo
import okhttp3.Headers
import okhttp3.Request
import java.util.Base64

data class SubscriptionRequestHeaderValues(
    val userAgent: String,
    val hardwareId: String,
    val deviceOs: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
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
    )

    fun applyRequestHeaders(
        builder: Request.Builder,
        values: SubscriptionRequestHeaderValues,
    ): Request.Builder = builder.apply {
        header(USER_AGENT, values.userAgent)
        header(X_HWID, values.hardwareId)
        values.deviceOs?.takeIf { it.isNotBlank() }?.let { header(X_DEVICE_OS, it) }
        values.osVersion?.takeIf { it.isNotBlank() }?.let { header(X_VER_OS, it) }
        values.deviceModel?.takeIf { it.isNotBlank() }?.let { header(X_DEVICE_MODEL, it) }
    }

    fun parseMetadata(headers: Headers): SubscriptionMetadata =
        SubscriptionMetadata(
            contentDisposition = normalizeNullableHeader(headers[CONTENT_DISPOSITION]),
            contentType = normalizeContentType(headers[CONTENT_TYPE]),
            profileTitle = decodeTextHeader(headers[PROFILE_TITLE]),
            profileUpdateIntervalHours = normalizeNullableHeader(headers[PROFILE_UPDATE_INTERVAL])?.toIntOrNull(),
            subscriptionUserInfo = parseSubscriptionUserInfo(headers[SUBSCRIPTION_USERINFO]),
            profileWebPageUrl = normalizeNullableHeader(headers[PROFILE_WEB_PAGE_URL]),
            announce = decodeTextHeader(headers[ANNOUNCE]),
            supportUrl = normalizeNullableHeader(headers[SUPPORT_URL]),
        )

    fun hasKnownResponseHeader(headers: Headers): Boolean =
        responseHeaderNames.any { headers[it] != null }

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

    fun decodeBase64ToUtf8(value: String): String? {
        val sanitized = value.trim().replace(WHITESPACE_REGEX, "")
        if (sanitized.isEmpty()) return null

        val candidates = buildList {
            add(sanitized)
            add(sanitized.padBase64())
        }.distinct()

        for (candidate in candidates) {
            runCatching {
                Base64.getDecoder().decode(candidate).toString(Charsets.UTF_8)
            }.getOrNull()?.let { return it }

            runCatching {
                Base64.getUrlDecoder().decode(candidate).toString(Charsets.UTF_8)
            }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun String.padBase64(): String {
        val padding = (4 - (length % 4)) % 4
        return this + "=".repeat(padding)
    }

    private const val BASE64_PREFIX = "base64:"
    private val WHITESPACE_REGEX = "\\s+".toRegex()
}
