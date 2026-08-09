package com.material.xray.data.parser

import java.util.Base64

/**
 * Decodes base64 that may use either the standard or the URL-safe alphabet, with or without
 * padding. Share links and subscription payloads in the wild mix all four combinations, so a
 * parser that accepts only one variant silently rejects valid configurations.
 */
internal fun decodeLenientBase64(value: String): ByteArray? {
    val sanitized = value.trim().replace(WHITESPACE_REGEX, "")
    if (sanitized.isEmpty()) return null

    val candidates = buildList {
        add(sanitized)
        add(sanitized.padBase64())
    }.distinct()

    for (candidate in candidates) {
        runCatching {
            Base64.getDecoder().decode(candidate)
        }.getOrNull()?.let { return it }

        runCatching {
            Base64.getUrlDecoder().decode(candidate)
        }.getOrNull()?.let { return it }
    }

    return null
}

internal fun decodeLenientBase64ToUtf8(value: String): String? = decodeLenientBase64(value)?.toString(Charsets.UTF_8)

private fun String.padBase64(): String {
    val padding = (4 - (length % 4)) % 4
    return this + "=".repeat(padding)
}

private val WHITESPACE_REGEX = "\\s+".toRegex()
