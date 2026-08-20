package com.material.xray.model

data class NotificationSettings(
    val enabled: Boolean = true,
    val updateIntervalMs: Int = DEFAULT_UPDATE_INTERVAL_MS,
    val style: NotificationStyle = NotificationStyle.default,
    val showTrafficSpeed: Boolean = false,
    val showRamUsage: Boolean = false,
    val showConnectionCount: Boolean = false,
    val showPing: Boolean = false,
    val showSessionTraffic: Boolean = false,
    val fieldOrder: List<NotificationField> = NotificationField.entries,
) {
    val anyFieldEnabled: Boolean
        get() = showTrafficSpeed || showRamUsage || showConnectionCount || showPing || showSessionTraffic

    /**
     * Whether the shared metrics poll has to run. Ping is left out: it is measured on its own far
     * slower schedule, so a notification that only shows a ping should not start the poll.
     */
    val needsMetricsPoll: Boolean
        get() = enabled && (showTrafficSpeed || showRamUsage || showConnectionCount || showSessionTraffic)

    val needsPingProbe: Boolean
        get() = enabled && showPing

    fun isFieldEnabled(field: NotificationField): Boolean = when (field) {
        NotificationField.TrafficSpeed -> showTrafficSpeed
        NotificationField.RamUsage -> showRamUsage
        NotificationField.ConnectionCount -> showConnectionCount
        NotificationField.Ping -> showPing
        NotificationField.SessionTraffic -> showSessionTraffic
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
    Ping,
    SessionTraffic,
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
