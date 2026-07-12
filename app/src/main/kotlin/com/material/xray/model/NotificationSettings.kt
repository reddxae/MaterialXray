package com.material.xray.model

data class NotificationSettings(
    val enabled: Boolean = true,
    val updateIntervalMs: Int = DEFAULT_UPDATE_INTERVAL_MS,
    val style: NotificationStyle = NotificationStyle.default,
    val showTrafficSpeed: Boolean = false,
    val showRamUsage: Boolean = false,
    val showConnectionCount: Boolean = false,
    val fieldOrder: List<NotificationField> = NotificationField.entries,
) {
    val anyFieldEnabled: Boolean
        get() = showTrafficSpeed || showRamUsage || showConnectionCount

    val hasDynamicMetrics: Boolean
        get() = enabled && anyFieldEnabled

    fun isFieldEnabled(field: NotificationField): Boolean = when (field) {
        NotificationField.TrafficSpeed -> showTrafficSpeed
        NotificationField.RamUsage -> showRamUsage
        NotificationField.ConnectionCount -> showConnectionCount
    }

    fun normalizedFieldOrder(): List<NotificationField> = (fieldOrder + NotificationField.entries).distinct()

    companion object {
        const val MIN_UPDATE_INTERVAL_MS = 100
        const val MAX_UPDATE_INTERVAL_MS = 5_000
        const val DEFAULT_UPDATE_INTERVAL_MS = 1_000
    }
}

enum class NotificationField {
    TrafficSpeed,
    RamUsage,
    ConnectionCount,
}

enum class NotificationStyle {
    Normal,
    Compact,
    ;

    companion object {
        val default = Normal

        fun fromValue(value: String?): NotificationStyle = entries.firstOrNull { it.name == value } ?: default
    }
}
