package com.material.xray.core.xray

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val XRAY_API_SOCKET_NAME_PREFIX = "material-xray-api"
internal const val XRAY_API_TIMEOUT_MS = 2_000L

internal const val XRAY_API_TAG = "api"

internal fun buildStatsApi(socketName: String = XRAY_API_SOCKET_NAME_PREFIX) = buildJsonObject {
    put("tag", XRAY_API_TAG)
    put("listen", "@$socketName")
    put("services", buildJsonArray { add("StatsService") })
}

internal fun buildStatsPolicy() = buildJsonObject {
    put("system", buildJsonObject {
        put("statsInboundUplink", true)
        put("statsInboundDownlink", true)
        put("statsOutboundUplink", true)
        put("statsOutboundDownlink", true)
    })
}

internal fun buildStatsConfig() = buildJsonObject { }
