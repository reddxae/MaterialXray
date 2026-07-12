package com.material.xray.model

enum class XrayLogLevel(
    val value: String,
) {
    Debug("debug"),
    Info("info"),
    Warning("warning"),
    Error("error"),
    None("none"),
    ;

    companion object {
        val default: XrayLogLevel = Error

        fun fromValue(value: String?): XrayLogLevel = entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: default
    }
}
