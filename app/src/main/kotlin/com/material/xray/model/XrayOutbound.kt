package com.material.xray.model

enum class XrayOutbound(
    val tag: String,
    val label: String,
    val description: String,
) {
    Proxy(
        tag = "proxy",
        label = "proxy",
        description = "Send traffic through the selected proxy server",
    ),
    Direct(
        tag = "direct",
        label = "direct",
        description = "Bypass the proxy and connect directly.",
    ),
    Block(
        tag = "block",
        label = "block",
        description = "Drop traffic with a blackhole outbound.",
    );

    companion object {
        val default = Proxy

        fun fromTag(tag: String?): XrayOutbound =
            entries.firstOrNull { it.tag == tag?.trim()?.lowercase() } ?: default
    }
}
