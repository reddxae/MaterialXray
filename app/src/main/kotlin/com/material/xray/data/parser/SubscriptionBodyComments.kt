package com.material.xray.data.parser

import okhttp3.Headers

/**
 * Metadata delivery for panels that cannot set HTTP response headers: the same known header names
 * may appear as `#key: value` comment lines inside the subscription body.
 */
object SubscriptionBodyComments {

    fun parse(body: String): Headers {
        val builder = Headers.Builder()
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith(COMMENT_PREFIX)) return@forEach
            val match = commentLineRegex.matchEntire(trimmed) ?: return@forEach
            val (name, value) = match.destructured
            if (name.lowercase() !in knownHeaderNames) return@forEach
            runCatching { builder.add(name, value) }
        }
        return builder.build()
    }

    /**
     * Layers body-comment metadata under the response headers: real headers keep precedence for
     * single-value lookups (`headers[name]` returns the last occurrence), while list-valued
     * headers such as `per-app-proxy-list` union both sources.
     */
    fun merge(responseHeaders: Headers, bodyHeaders: Headers): Headers {
        if (bodyHeaders.size == 0) return responseHeaders
        val builder = Headers.Builder()
        // OkHttp resolves duplicate names to the last value, so response headers must be appended
        // after the body comments to win.
        bodyHeaders.forEach { (name, value) -> runCatching { builder.add(name, value) } }
        responseHeaders.forEach { (name, value) -> runCatching { builder.add(name, value) } }
        return builder.build()
    }

    private const val COMMENT_PREFIX = "#"
    private val commentLineRegex = "^#\\s*([a-zA-Z0-9-]+)\\s*:\\s*(.*?)\\s*$".toRegex()
    private val knownHeaderNames = SubscriptionStandardHeaders.responseHeaderNames
        .map(String::lowercase)
        .toSet()
}
