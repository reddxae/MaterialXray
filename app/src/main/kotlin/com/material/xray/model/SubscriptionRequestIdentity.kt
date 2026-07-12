package com.material.xray.model

/**
 * Default User-Agent advertised when the [SubscriptionUserAgentMode.HAPP] preset is selected.
 * Providers that gate their subscription content typically only check the `Happ/` prefix, but a
 * realistic recent version is used to better blend in. Bump this when refreshing the preset.
 */
const val HAPP_USER_AGENT = "Happ/3.23.0"

/**
 * How the User-Agent (and accompanying identification headers) are produced when fetching a
 * subscription.
 */
enum class SubscriptionUserAgentMode(
    val value: String,
) {
    AUTO(
        value = "auto",
    ),
    HAPP(
        value = "happ",
    ),
    CUSTOM(
        value = "custom",
    ),
    ;

    companion object {
        val default = AUTO

        fun fromValue(value: String?): SubscriptionUserAgentMode = entries.firstOrNull {
            it.value.equals(value, ignoreCase = true)
        } ?: default
    }
}

/** A single custom request header (name/value pair) used by [SubscriptionUserAgentMode.CUSTOM]. */
data class SubscriptionHeader(
    val name: String,
    val value: String,
)

/**
 * Resolved settings describing how an outgoing subscription request should be identified.
 *
 * This is a snapshot of the user's global preferences; [SubscriptionFetcher] translates it into
 * concrete HTTP headers (filling in device info for the [SubscriptionUserAgentMode.AUTO] and
 * [SubscriptionUserAgentMode.HAPP] presets).
 */
data class SubscriptionRequestIdentity(
    val mode: SubscriptionUserAgentMode = SubscriptionUserAgentMode.AUTO,
    val sendHardwareId: Boolean = true,
    val customUserAgent: String = "",
    val customHeaders: List<SubscriptionHeader> = emptyList(),
)

/**
 * Parses free-form header text (one `Name: Value` pair per line) into structured headers.
 * Blank lines and lines without a name are ignored.
 */
fun parseSubscriptionHeaders(raw: String): List<SubscriptionHeader> = raw
    .lineSequence()
    .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val name = trimmed.substring(0, separator).trim()
        if (name.isEmpty()) return@mapNotNull null
        SubscriptionHeader(name = name, value = trimmed.substring(separator + 1).trim())
    }
    .toList()
