package com.material.xray.model

enum class PingMethod(
    val value: String,
) {
    Httping(
        value = "httping",
    ),
    Tcping(
        value = "tcping",
    ),
    ;

    companion object {
        val default = Httping

        fun fromValue(value: String?): PingMethod = entries.firstOrNull { it.value == value } ?: default
    }
}
