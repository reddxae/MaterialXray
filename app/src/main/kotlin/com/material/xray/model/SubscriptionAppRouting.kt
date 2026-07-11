package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionAppRouting(
    val packageNames: List<String>,
    val mode: SubscriptionAppRoutingMode,
) {
    fun normalized(): SubscriptionAppRouting? {
        val normalizedPackages = packageNames
            .map { it.trim() }
            .filter { PACKAGE_NAME_REGEX.matches(it) }
            .distinct()
        return copy(packageNames = normalizedPackages).takeIf { normalizedPackages.isNotEmpty() }
    }

    companion object {
        private val PACKAGE_NAME_REGEX = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    }
}

@Serializable
enum class SubscriptionAppRoutingMode(val persistedValue: String) {
    Direct("direct"),
    DefaultSelected("default_selected"),
    DefaultOutbound("default_outbound"),
    ;

    companion object {
        fun fromHeader(value: String?): SubscriptionAppRoutingMode? = when (value?.trim()?.lowercase()?.replace('-', '_')) {
            "bypass", "direct" -> Direct
            "default", "default_selected", "proxy" -> DefaultSelected
            "default_outbound", "inherit" -> DefaultOutbound
            else -> null
        }

        fun fromPersisted(value: String?): SubscriptionAppRoutingMode? = when (value?.trim()?.lowercase()) {
            "bypass" -> Direct
            else -> entries.firstOrNull { it.persistedValue == value?.trim()?.lowercase() }
        }
    }
}

enum class RoutingPolicyControl(
    val value: String,
    val label: String,
    val description: String,
) {
    User(
        value = "user",
        label = "User-controlled",
        description = "Keep routing rules and app routing under manual control",
    ),
    SubscriptionProvider(
        value = "subscription_provider",
        label = "Subscription provider",
        description = "Apply routing rules and per-app routing from the selected subscription provider",
    ),
    ;

    companion object {
        val default = SubscriptionProvider

        fun fromValue(value: String?): RoutingPolicyControl = entries.firstOrNull { it.value == value } ?: default
    }
}
