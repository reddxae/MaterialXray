package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
enum class RootConnectionBackend(val persistedValue: String) {
    Tun("tun"),
    Tproxy("tproxy"),
    ;

    companion object {
        val default = Tproxy

        fun fromValue(value: String?): RootConnectionBackend = entries.firstOrNull {
            it.persistedValue == value
        } ?: default
    }
}
