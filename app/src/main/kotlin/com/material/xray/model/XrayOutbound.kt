package com.material.xray.model

enum class XrayOutbound(
    val tag: String,
) {
    Proxy(
        tag = "proxy",
    ),
    Direct(
        tag = "direct",
    ),
    Block(
        tag = "block",
    ),
    ;

    companion object {
        val default = Proxy

        fun fromTag(tag: String?): XrayOutbound = fromTagOrNull(tag) ?: default

        fun fromTagOrNull(tag: String?): XrayOutbound? = entries.firstOrNull { it.tag == tag?.trim()?.lowercase() }
    }
}
