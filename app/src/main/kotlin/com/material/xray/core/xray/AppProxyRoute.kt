package com.material.xray.core.xray

import com.material.xray.model.ServerConfig

data class AppProxyRoute(
    val inboundTag: String,
    val tunName: String,
    val outboundTag: String,
    val server: ServerConfig,
    val applyRoutingRules: Boolean = false,
)
