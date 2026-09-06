package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionAppRouting(
    val packageNames: List<String>,
    val mode: SubscriptionAppRoutingMode,
    val inverted: Boolean = false,
) {
    fun normalized(): SubscriptionAppRouting? {
        val normalizedPackages = packageNames
            .map { it.trim() }
            .filter { PACKAGE_NAME_REGEX.matches(it) }
            .distinct()
        return copy(packageNames = normalizedPackages).takeIf { normalizedPackages.isNotEmpty() }
    }

    /**
     * Mode a package should be assigned to, or null when the package stays on the default route.
     * An inverted list flips the meaning: listed packages get the opposite of [mode] while every
     * other installed package follows [mode] itself.
     */
    fun assignmentModeFor(packageName: String): SubscriptionAppRoutingMode? = when {
        packageNames.contains(packageName) -> if (inverted) mode.inverted() else mode
        inverted -> mode
        else -> null
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

    fun inverted(): SubscriptionAppRoutingMode = when (this) {
        Direct -> DefaultSelected
        DefaultSelected, DefaultOutbound -> Direct
    }

    companion object {
        fun fromHeader(value: String?): SubscriptionAppRoutingMode? = when (value?.trim()?.lowercase()?.replace('-', '_')) {
            "bypass", "direct", "off" -> Direct
            "on", "default", "default_selected", "proxy" -> DefaultSelected
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
) {
    User(
        value = "user",
    ),
    SubscriptionProvider(
        value = "subscription_provider",
    ),
    ;

    companion object {
        val default = SubscriptionProvider

        fun fromValue(value: String?): RoutingPolicyControl = entries.firstOrNull { it.value == value } ?: default
    }
}
