package com.material.xray.model

enum class PingMethod(
    val value: String,
    val label: String,
    val description: String,
) {
    Httping(
        value = "httping",
        label = "httping (recommended)",
        description = "End-to-end test. Ensures that the configuration is valid, and measures real-world delay.",
    ),
    Tcping(
        value = "tcping",
        label = "tcping",
        description = "TCP handshake test. Only indicates basic connectivity status and one-way delay.",
    );

    companion object {
        val default = Httping

        fun fromValue(value: String?): PingMethod =
            entries.firstOrNull { it.value == value } ?: default
    }
}
