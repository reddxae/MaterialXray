package com.materialxray.model

enum class XrayLogLevel(
    val value: String,
    val label: String,
) {
    Debug("debug", "Debug"),
    Info("info", "Info"),
    Warning("warning", "Warning"),
    Error("error", "Error"),
    None("none", "None");

    companion object {
        val default: XrayLogLevel = Error

        fun fromValue(value: String?): XrayLogLevel =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: default
    }
}
