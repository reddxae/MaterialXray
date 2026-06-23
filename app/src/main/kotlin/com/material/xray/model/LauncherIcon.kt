package com.material.xray.model

enum class LauncherIcon(
    val value: String,
    val label: String,
    val aliasClassName: String,
) {
    Default(
        value = "default",
        label = "Default",
        aliasClassName = "DefaultLauncherAlias",
    ),
    Material(
        value = "material",
        label = "Material",
        aliasClassName = "MaterialLauncherAlias",
    ),
    ;

    companion object {
        val default = Default

        fun fromValue(value: String?): LauncherIcon = entries.firstOrNull { it.value == value } ?: default
    }
}
