package com.material.xray.data.db.entity

import androidx.room.Entity

@Entity(tableName = "app_bypass", primaryKeys = ["profileId", "packageName"])
data class AppBypassEntity(
    val packageName: String,
    val profileId: Int = 0,
    val uid: Int,
    val excluded: Boolean = true,
    val serverId: Long? = null,
    val manual: Boolean = true,
    val routeMode: String? = null,
)

enum class AppRouteMode(val persistedValue: String?) {
    DefaultSelected("default_selected"),
    DefaultOutbound("default_outbound"),
    Direct("direct"),
    Bypass("bypass"),
    Server("server"),
}

data class AppRouteAssignment(
    val mode: AppRouteMode,
    val serverId: Long? = null,
)

fun AppBypassEntity.routeAssignment(): AppRouteAssignment =
    when {
        excluded || routeMode == AppRouteMode.Bypass.persistedValue -> {
            AppRouteAssignment(AppRouteMode.Bypass)
        }
        routeMode == AppRouteMode.Direct.persistedValue -> {
            AppRouteAssignment(AppRouteMode.Direct)
        }
        routeMode == AppRouteMode.DefaultOutbound.persistedValue -> {
            AppRouteAssignment(AppRouteMode.DefaultOutbound)
        }
        serverId != null -> {
            AppRouteAssignment(AppRouteMode.Server, serverId)
        }
        else -> {
            AppRouteAssignment(AppRouteMode.DefaultSelected)
        }
    }

fun AppBypassEntity.isManualRouteOverride(): Boolean =
    manual && routeAssignment().mode != AppRouteMode.DefaultSelected

fun AppRouteAssignment.toAppBypassEntity(
    packageName: String,
    profileId: Int,
    uid: Int,
    manual: Boolean,
): AppBypassEntity =
    AppBypassEntity(
        packageName = packageName,
        profileId = profileId,
        uid = uid,
        excluded = mode == AppRouteMode.Bypass,
        serverId = serverId.takeIf { mode == AppRouteMode.Server },
        manual = manual,
        routeMode = mode.persistedValue,
    )
