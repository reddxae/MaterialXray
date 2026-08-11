package com.material.xray.core.xray

import com.material.xray.model.XrayRuntimeSettings
import kotlinx.serialization.json.JsonObject

sealed interface XrayInbound {
    val tag: String

    data class Tun(
        val name: String,
        override val tag: String,
        val mtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
    ) : XrayInbound

    data class Tproxy(
        val port: Int,
        override val tag: String,
        val outboundMark: Int,
        val allowIpv6: Boolean,
    ) : XrayInbound
}

internal fun XrayInbound.toJson(): JsonObject = when (this) {
    is XrayInbound.Tun -> buildTunInbound(name, tag, mtu)
    is XrayInbound.Tproxy -> buildTproxyInbound(port, tag, outboundMark, allowIpv6)
}
