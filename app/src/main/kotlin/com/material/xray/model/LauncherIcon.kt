package com.material.xray.model

enum class LauncherIcon(
    val value: String,
    val aliasClassName: String,
) {
    Default(
        value = "default",
        aliasClassName = "DefaultLauncherAlias",
    ),
    Material(
        value = "material",
        aliasClassName = "MaterialLauncherAlias",
    ),
    ;

    companion object {
        val default = Default

        fun fromValue(value: String?): LauncherIcon = entries.firstOrNull { it.value == value } ?: default
    }
}
