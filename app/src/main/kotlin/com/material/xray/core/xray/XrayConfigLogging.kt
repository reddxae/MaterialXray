package com.material.xray.core.xray

import com.material.xray.model.XrayLogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun buildLogConfig(logLevel: XrayLogLevel) = buildJsonObject {
    put("access", "none")
    put("loglevel", logLevel.value)
}
