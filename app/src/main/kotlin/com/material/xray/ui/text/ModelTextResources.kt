package com.material.xray.ui.text

import androidx.annotation.StringRes
import com.material.xray.R
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationStyle
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound

@get:StringRes
val PingMethod.labelResource: Int
    get() = when (this) {
        PingMethod.Httping -> R.string.ping_method_httping_label
        PingMethod.Tcping -> R.string.ping_method_tcping_label
    }

@get:StringRes
val PingMethod.descriptionResource: Int
    get() = when (this) {
        PingMethod.Httping -> R.string.ping_method_httping_description
        PingMethod.Tcping -> R.string.ping_method_tcping_description
    }

@get:StringRes
val SubscriptionUserAgentMode.labelResource: Int
    get() = when (this) {
        SubscriptionUserAgentMode.AUTO -> R.string.subscription_user_agent_automatic_label
        SubscriptionUserAgentMode.HAPP -> R.string.subscription_user_agent_happ_label
        SubscriptionUserAgentMode.CUSTOM -> R.string.subscription_user_agent_custom_label
    }

@get:StringRes
val SubscriptionUserAgentMode.descriptionResource: Int
    get() = when (this) {
        SubscriptionUserAgentMode.AUTO -> R.string.subscription_user_agent_automatic_description
        SubscriptionUserAgentMode.HAPP -> R.string.subscription_user_agent_happ_description
        SubscriptionUserAgentMode.CUSTOM -> R.string.subscription_user_agent_custom_description
    }

@get:StringRes
val RoutingPolicyControl.labelResource: Int
    get() = when (this) {
        RoutingPolicyControl.User -> R.string.routing_policy_user_label
        RoutingPolicyControl.SubscriptionProvider -> R.string.routing_policy_subscription_provider_label
    }

@get:StringRes
val RoutingPolicyControl.descriptionResource: Int
    get() = when (this) {
        RoutingPolicyControl.User -> R.string.routing_policy_user_description
        RoutingPolicyControl.SubscriptionProvider -> R.string.routing_policy_subscription_provider_description
    }

@get:StringRes
val XrayOutbound.labelResource: Int
    get() = when (this) {
        XrayOutbound.Proxy -> R.string.xray_outbound_proxy_label
        XrayOutbound.Direct -> R.string.xray_outbound_direct_label
        XrayOutbound.Block -> R.string.xray_outbound_block_label
    }

@get:StringRes
val XrayOutbound.descriptionResource: Int
    get() = when (this) {
        XrayOutbound.Proxy -> R.string.xray_outbound_proxy_description
        XrayOutbound.Direct -> R.string.xray_outbound_direct_description
        XrayOutbound.Block -> R.string.xray_outbound_block_description
    }

/** Describes what happens when a routing rule with no matching condition sends traffic here. */
@get:StringRes
val XrayOutbound.catchAllEffectResource: Int
    get() = when (this) {
        XrayOutbound.Proxy -> R.string.routing_catch_all_effect_proxy
        XrayOutbound.Direct -> R.string.routing_catch_all_effect_direct
        XrayOutbound.Block -> R.string.routing_catch_all_effect_block
    }

@get:StringRes
val XrayLogLevel.labelResource: Int
    get() = when (this) {
        XrayLogLevel.Debug -> R.string.xray_log_level_debug
        XrayLogLevel.Info -> R.string.xray_log_level_info
        XrayLogLevel.Warning -> R.string.xray_log_level_warning
        XrayLogLevel.Error -> R.string.xray_log_level_error
        XrayLogLevel.None -> R.string.xray_log_level_none
    }

@get:StringRes
val LauncherIcon.labelResource: Int
    get() = when (this) {
        LauncherIcon.Default -> R.string.launcher_icon_default
        LauncherIcon.Material -> R.string.launcher_icon_material
    }

@get:StringRes
val NotificationField.labelResource: Int
    get() = when (this) {
        NotificationField.TrafficSpeed -> R.string.notification_field_traffic_speed_label
        NotificationField.RamUsage -> R.string.notification_field_ram_usage_label
        NotificationField.ConnectionCount -> R.string.notification_field_connection_count_label
    }

@get:StringRes
val NotificationField.descriptionResource: Int
    get() = when (this) {
        NotificationField.TrafficSpeed -> R.string.notification_field_traffic_speed_description
        NotificationField.RamUsage -> R.string.notification_field_ram_usage_description
        NotificationField.ConnectionCount -> R.string.notification_field_connection_count_description
    }

@get:StringRes
val NotificationStyle.labelResource: Int
    get() = when (this) {
        NotificationStyle.Normal -> R.string.notification_style_normal_label
        NotificationStyle.Compact -> R.string.notification_style_compact_label
    }

@get:StringRes
val NotificationStyle.descriptionResource: Int
    get() = when (this) {
        NotificationStyle.Normal -> R.string.notification_style_normal_description
        NotificationStyle.Compact -> R.string.notification_style_compact_description
    }
