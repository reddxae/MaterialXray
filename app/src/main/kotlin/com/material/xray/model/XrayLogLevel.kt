package com.material.xray.model

enum class XrayLogLevel(
    val value: String,
    val label: String,
) {
    Debug("debug", "debug"),
    Info("info", "info"),
    Warning("warning", "warning"),
    Error("error", "error"),
    None("none", "none"),
    ;

    companion object {
        val default: XrayLogLevel = Error

        fun fromValue(value: String?): XrayLogLevel = entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: default
    }
}
