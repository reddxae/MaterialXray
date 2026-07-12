package com.material.xray.core.xray

import com.material.xray.model.XrayRuntimeSettings
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val XRAY_API_SOCKET_NAME_PREFIX = "material-xray-api"
internal const val XRAY_API_TIMEOUT_MS = 2_000L

internal const val XRAY_API_TAG = "api"

internal fun buildStatsApi(
    socketName: String = XRAY_API_SOCKET_NAME_PREFIX,
    enableObservatory: Boolean = false,
) = buildJsonObject {
    put("tag", XRAY_API_TAG)
    put("listen", "@$socketName")
    put(
        "services",
        buildJsonArray {
            add("StatsService")
            add("RoutingService")
            if (enableObservatory) add("ObservatoryService")
        },
    )
}

internal fun buildStatsPolicy(
    xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
) = buildJsonObject {
    put(
        "levels",
        buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put("bufferSize", xrayBufferSizeKiB)
                },
            )
        },
    )
    put(
        "system",
        buildJsonObject {
            put("statsInboundUplink", true)
            put("statsInboundDownlink", true)
            put("statsOutboundUplink", true)
            put("statsOutboundDownlink", true)
        },
    )
}

internal fun buildStatsConfig() = buildJsonObject { }
