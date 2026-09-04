package com.material.xray.core.xray

import com.material.xray.model.XrayRuntimeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val XRAY_API_SOCKET_NAME_PREFIX = "material-xray-api"
internal const val XRAY_API_TIMEOUT_MS = 2_000L

internal const val XRAY_API_TAG = "api"

internal fun buildStatsApi(
    endpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
    enableObservatory: Boolean = false,
) = buildJsonObject {
    put("tag", XRAY_API_TAG)
    when (endpoint) {
        is XrayApiEndpoint.UnixSocket -> put("listen", "@${endpoint.name}")
        is XrayApiEndpoint.FileSystemUnixSocket -> put("listen", endpoint.path)
        is XrayApiEndpoint.LoopbackTcp -> put("listen", "$XRAY_API_LOOPBACK_ADDRESS:${endpoint.port}")
    }
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

internal fun parseXrayApiEndpoint(configJson: String): XrayApiEndpoint? = runCatching {
    val root = Json.parseToJsonElement(configJson) as? JsonObject
    val api = root?.get("api") as? JsonObject
    val listen = api
        ?.get("listen")
        ?.jsonPrimitive
        ?.contentOrNull
    when {
        listen?.startsWith('@') == true -> listen.drop(1)
            .takeIf { it.isNotBlank() }
            ?.let { XrayApiEndpoint.UnixSocket(it) }
        listen?.startsWith('/') == true -> XrayApiEndpoint.FileSystemUnixSocket(listen)
        listen?.startsWith("$XRAY_API_LOOPBACK_ADDRESS:") == true ->
            listen
                .removePrefix("$XRAY_API_LOOPBACK_ADDRESS:")
                .toIntOrNull()
                ?.takeIf { it in 1..65_535 }
                ?.let { XrayApiEndpoint.LoopbackTcp(it) }
        else -> null
    }
}.getOrNull()
