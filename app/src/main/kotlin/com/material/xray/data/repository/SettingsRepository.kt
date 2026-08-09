package com.material.xray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.model.normalizeDnsServersForIpv6
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(SettingsDefaultMigration()) },
)

@Singleton
@Suppress("TooManyFunctions")
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val DOMESTIC_DNS_SERVERS = stringPreferencesKey("domestic_dns_servers")
        val FWMARK = intPreferencesKey("fwmark")
        val ROUTE_TABLE = intPreferencesKey("route_table")
        val XRAY_BUFFER_SIZE_KIB = intPreferencesKey("xray_buffer_size_kib")
        val TUN_MTU = intPreferencesKey("tun_mtu")
        val XRAY_MEMORY_RESTART_THRESHOLD_MIB = intPreferencesKey("xray_memory_restart_threshold_mib")
        val PASSIVE_HEALTH_MONITORING_ENABLED = booleanPreferencesKey("passive_health_monitoring_enabled")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val ALLOW_IPV6 = booleanPreferencesKey("allow_ipv6")
        val LAST_SERVER_ID = longPreferencesKey("last_server_id")
        val GEOIP_URL = stringPreferencesKey("geoip_url")
        val GEOSITE_URL = stringPreferencesKey("geosite_url")
        val LATENCY_CHECK_URL = stringPreferencesKey("latency_check_url")
        val DEFAULT_PING_METHOD = stringPreferencesKey("default_ping_method")
        val SORT_OUTBOUNDS_BY_LATENCY = booleanPreferencesKey("sort_outbounds_by_latency")
        val XRAY_LOG_LEVEL = stringPreferencesKey("xray_log_level")
        val LAST_XRAY_LOG_LEVEL = stringPreferencesKey("last_xray_log_level")
        val DEFAULT_OUTBOUND = stringPreferencesKey("default_outbound")
        val LAUNCHER_ICON = stringPreferencesKey("launcher_icon")
        val SHOW_ADVANCED_OPTIONS = booleanPreferencesKey("show_advanced_options")
        val APP_SPECIFIC_SERVER_NOTE_SHOWN = booleanPreferencesKey("app_specific_server_note_shown")
        val ROUTING_POLICY_CONTROL = stringPreferencesKey("routing_policy_control")
        val ROUTING_RULES = stringPreferencesKey("routing_rules")
        val ROUTING_RULES_VERSION = intPreferencesKey("routing_rules_version")
        val ROUTING_RULE_STATES = stringPreferencesKey("routing_rule_states")
        val DELETED_DEFAULT_ROUTING_RULE_IDS = stringSetPreferencesKey("deleted_default_routing_rule_ids")
        val ROUTING_DOMAIN_STRATEGY = stringPreferencesKey("routing_domain_strategy")
        val ROUTING_DOMAIN_MATCHER = stringPreferencesKey("routing_domain_matcher")
        val ROUTING_FALLBACK_OUTBOUND = stringPreferencesKey("routing_fallback_outbound")
        val USE_ROOT_SERVICE = booleanPreferencesKey("use_root_service")
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        val NOTIFICATION_UPDATE_INTERVAL_MS = intPreferencesKey("notification_update_interval_ms")
        val NOTIFICATION_STYLE = stringPreferencesKey("notification_style")
        val NOTIFICATION_SHOW_TRAFFIC_SPEED = booleanPreferencesKey("notification_show_traffic_speed")
        val NOTIFICATION_SHOW_RAM_USAGE = booleanPreferencesKey("notification_show_ram_usage")
        val NOTIFICATION_SHOW_CONNECTION_COUNT = booleanPreferencesKey("notification_show_connection_count")
        val NOTIFICATION_FIELD_ORDER = stringPreferencesKey("notification_field_order")
        val SUBSCRIPTION_SEND_HWID = booleanPreferencesKey("subscription_send_hwid")
        val SUBSCRIPTION_PREFER_JSON = booleanPreferencesKey("subscription_prefer_json")
        val APP_UPDATE_CHECKS_ENABLED = booleanPreferencesKey("app_update_checks_enabled")
        private val LEGACY_GEO_DATA_BASE_URL = stringPreferencesKey("geo_data_base_url")
        private const val CURRENT_ROUTING_RULES_VERSION = 2

        const val DEFAULT_GEOIP_URL =
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
        const val DEFAULT_GEOSITE_URL =
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
        const val DEFAULT_LATENCY_CHECK_URL = "https://gstatic.com/generate_204"
        const val DEFAULT_DNS_SERVERS = "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query"
        const val DEFAULT_DOMESTIC_DNS_SERVERS = "77.88.8.8,77.88.8.1"
        const val DEFAULT_PASSIVE_HEALTH_MONITORING_ENABLED = true
        const val DEFAULT_TUN_NAME = ""
    }

    val tunName: Flow<String> = store.data.map { it[TUN_NAME] ?: DEFAULT_TUN_NAME }
    val dnsServers: Flow<String> = store.data.map { it[DNS_SERVERS] ?: DEFAULT_DNS_SERVERS }
    val domesticDnsServers: Flow<String> = store.data.map {
        it[DOMESTIC_DNS_SERVERS] ?: DEFAULT_DOMESTIC_DNS_SERVERS
    }
    val fwmark: Flow<Int> = store.data.map { it[FWMARK] ?: 255 }
    val routeTable: Flow<Int> = store.data.map { it[ROUTE_TABLE] ?: 100 }
    val xrayBufferSizeKiB: Flow<Int> = store.data.map { prefs ->
        XrayRuntimeSettings.normalizeXrayBufferSizeKiB(prefs[XRAY_BUFFER_SIZE_KIB])
    }
    val tunMtu: Flow<Int> = store.data.map { prefs ->
        XrayRuntimeSettings.normalizeTunMtu(prefs[TUN_MTU])
    }
    val xrayMemoryRestartThresholdMiB: Flow<Int> = store.data.map { prefs ->
        XrayRuntimeSettings.normalizeXrayMemoryRestartThresholdMiB(prefs[XRAY_MEMORY_RESTART_THRESHOLD_MIB])
    }
    val passiveHealthMonitoringEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[PASSIVE_HEALTH_MONITORING_ENABLED] ?: DEFAULT_PASSIVE_HEALTH_MONITORING_ENABLED
    }
    val autoConnect: Flow<Boolean> = store.data.map { it[AUTO_CONNECT] ?: false }
    val bypassLan: Flow<Boolean> = store.data.map { it[BYPASS_LAN] ?: true }
    val allowIpv6: Flow<Boolean> = store.data.map { it[ALLOW_IPV6] ?: false }
    val lastServerId: Flow<Long> = store.data.map { it[LAST_SERVER_ID] ?: -1L }
    val xrayLogLevel: Flow<XrayLogLevel> = store.data.map { prefs ->
        if (prefs[SHOW_ADVANCED_OPTIONS] == true) {
            XrayLogLevel.fromValue(prefs[XRAY_LOG_LEVEL] ?: prefs[LAST_XRAY_LOG_LEVEL])
        } else {
            XrayLogLevel.None
        }
    }
    val defaultOutbound: Flow<XrayOutbound> = store.data.map { prefs ->
        XrayOutbound.fromTag(prefs[DEFAULT_OUTBOUND])
    }
    val launcherIcon: Flow<LauncherIcon> = store.data.map { prefs ->
        LauncherIcon.fromValue(prefs[LAUNCHER_ICON])
    }
    val showAdvancedOptions: Flow<Boolean> = store.data.map { prefs ->
        prefs[SHOW_ADVANCED_OPTIONS] ?: false
    }
    val appSpecificServerNoteShown: Flow<Boolean> = store.data.map { prefs ->
        prefs[APP_SPECIFIC_SERVER_NOTE_SHOWN] ?: false
    }
    val routingPolicyControl: Flow<RoutingPolicyControl> = store.data.map { prefs ->
        RoutingPolicyControl.fromValue(prefs[ROUTING_POLICY_CONTROL])
    }
    val useRootService: Flow<Boolean> = store.data.map { prefs ->
        prefs[USE_ROOT_SERVICE] ?: false
    }
    val geoipUrl: Flow<String> = store.data.map { prefs ->
        prefs[GEOIP_URL]
            ?: prefs[LEGACY_GEO_DATA_BASE_URL]?.let { legacyBaseUrl -> appendLegacyFileName(legacyBaseUrl, "geoip.dat") }
            ?: DEFAULT_GEOIP_URL
    }
    val geositeUrl: Flow<String> = store.data.map { prefs ->
        prefs[GEOSITE_URL]
            ?: prefs[LEGACY_GEO_DATA_BASE_URL]?.let { legacyBaseUrl -> appendLegacyFileName(legacyBaseUrl, "geosite.dat") }
            ?: DEFAULT_GEOSITE_URL
    }
    val latencyCheckUrl: Flow<String> = store.data.map { prefs ->
        prefs[LATENCY_CHECK_URL] ?: DEFAULT_LATENCY_CHECK_URL
    }
    val defaultPingMethod: Flow<PingMethod> = store.data.map { prefs ->
        PingMethod.fromValue(prefs[DEFAULT_PING_METHOD])
    }
    val sortOutboundsByLatency: Flow<Boolean> = store.data.map { prefs ->
        prefs[SORT_OUTBOUNDS_BY_LATENCY] ?: false
    }
    val routingRules: Flow<List<RoutingRule>> = store.data.map { prefs ->
        decodeRoutingRules(
            rulesEncoded = prefs[ROUTING_RULES],
            rulesVersion = prefs[ROUTING_RULES_VERSION],
            statesEncoded = prefs[ROUTING_RULE_STATES],
            deletedDefaultRuleIds = prefs[DELETED_DEFAULT_ROUTING_RULE_IDS].orEmpty(),
        )
    }
    val routingDomainStrategy: Flow<String> = store.data.map { prefs ->
        SubscriptionRouting.normalizeDomainStrategy(prefs[ROUTING_DOMAIN_STRATEGY])
    }
    val routingDomainMatcher: Flow<String?> = store.data.map { prefs ->
        SubscriptionRouting.normalizeDomainMatcher(prefs[ROUTING_DOMAIN_MATCHER])
    }
    val routingFallbackOutbound: Flow<XrayOutbound?> = store.data.map { prefs ->
        XrayOutbound.fromTagOrNull(prefs[ROUTING_FALLBACK_OUTBOUND])
    }
    val notificationSettings: Flow<NotificationSettings> = store.data.map { prefs ->
        NotificationSettings(
            enabled = prefs[NOTIFICATION_ENABLED] ?: true,
            updateIntervalMs = (prefs[NOTIFICATION_UPDATE_INTERVAL_MS] ?: NotificationSettings.DEFAULT_UPDATE_INTERVAL_MS)
                .coerceIn(NotificationSettings.MIN_UPDATE_INTERVAL_MS, NotificationSettings.MAX_UPDATE_INTERVAL_MS),
            style = NotificationStyle.fromValue(prefs[NOTIFICATION_STYLE]),
            showTrafficSpeed = prefs[NOTIFICATION_SHOW_TRAFFIC_SPEED] ?: false,
            showRamUsage = prefs[NOTIFICATION_SHOW_RAM_USAGE] ?: false,
            showConnectionCount = prefs[NOTIFICATION_SHOW_CONNECTION_COUNT] ?: false,
            fieldOrder = decodeNotificationFieldOrder(prefs[NOTIFICATION_FIELD_ORDER]),
        )
    }

    val subscriptionSendHardwareId: Flow<Boolean> = store.data.map { prefs ->
        prefs[SUBSCRIPTION_SEND_HWID] ?: true
    }
    val legacySubscriptionPreferJson: Flow<Boolean> = store.data.map { prefs ->
        prefs[SUBSCRIPTION_PREFER_JSON] ?: true
    }
    val appUpdateChecksEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[APP_UPDATE_CHECKS_ENABLED] ?: true
    }

    suspend fun runtimeSettingsSnapshot(): XrayRuntimeSettings = XrayRuntimeSettings(
        tunName = tunName.first(),
        fwmark = fwmark.first(),
        routeTable = routeTable.first(),
        useRootService = useRootService.first(),
        dnsServers = dnsServers.first(),
        domesticDnsServers = domesticDnsServers.first(),
        logLevel = xrayLogLevel.first(),
        defaultOutbound = defaultOutbound.first(),
        bypassLan = bypassLan.first(),
        allowIpv6 = allowIpv6.first(),
        routingRules = routingRules.first(),
        xrayBufferSizeKiB = xrayBufferSizeKiB.first(),
        tunMtu = tunMtu.first(),
        routingDomainStrategy = routingDomainStrategy.first(),
        routingDomainMatcher = routingDomainMatcher.first(),
        routingFallbackOutbound = routingFallbackOutbound.first(),
    )

    suspend fun setTunName(name: String) = store.edit { it[TUN_NAME] = name }
    suspend fun setDnsServers(servers: String) = store.edit { prefs ->
        prefs[DNS_SERVERS] = normalizeDnsServersForIpv6(servers, prefs[ALLOW_IPV6] ?: false)
    }
    suspend fun setDomesticDnsServers(servers: String) = store.edit { prefs ->
        prefs[DOMESTIC_DNS_SERVERS] = normalizeDnsServersForIpv6(servers, prefs[ALLOW_IPV6] ?: false)
    }
    suspend fun setXrayBufferSizeKiB(bufferSizeKiB: Int) {
        require(XrayRuntimeSettings.isValidXrayBufferSizeKiB(bufferSizeKiB))
        store.edit { it[XRAY_BUFFER_SIZE_KIB] = bufferSizeKiB }
    }
    suspend fun setTunMtu(mtu: Int) {
        require(XrayRuntimeSettings.isValidTunMtu(mtu))
        store.edit { it[TUN_MTU] = mtu }
    }
    suspend fun setXrayMemoryRestartThresholdMiB(thresholdMiB: Int) {
        require(XrayRuntimeSettings.isValidXrayMemoryRestartThresholdMiB(thresholdMiB))
        store.edit { it[XRAY_MEMORY_RESTART_THRESHOLD_MIB] = thresholdMiB }
    }
    suspend fun setPassiveHealthMonitoringEnabled(enabled: Boolean) = store.edit {
        it[PASSIVE_HEALTH_MONITORING_ENABLED] = enabled
    }
    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[AUTO_CONNECT] = enabled }
    suspend fun setBypassLan(enabled: Boolean) = store.edit { it[BYPASS_LAN] = enabled }
    suspend fun setAllowIpv6(enabled: Boolean) = store.edit { prefs ->
        prefs[DNS_SERVERS] = normalizeDnsServersForIpv6(prefs[DNS_SERVERS] ?: DEFAULT_DNS_SERVERS, enabled)
        prefs[DOMESTIC_DNS_SERVERS] = normalizeDnsServersForIpv6(
            prefs[DOMESTIC_DNS_SERVERS] ?: DEFAULT_DOMESTIC_DNS_SERVERS,
            enabled,
        )
        prefs[ALLOW_IPV6] = enabled
    }
    suspend fun setLastServerId(id: Long) = store.edit { it[LAST_SERVER_ID] = id }
    suspend fun compareAndSetLastServerId(expectedId: Long, id: Long): Boolean {
        var updated = false
        store.edit { preferences ->
            if ((preferences[LAST_SERVER_ID] ?: -1L) == expectedId) {
                preferences[LAST_SERVER_ID] = id
                updated = true
            }
        }
        return updated
    }
    suspend fun setXrayLogLevel(level: XrayLogLevel) = store.edit { prefs ->
        prefs[XRAY_LOG_LEVEL] = level.value
        prefs[LAST_XRAY_LOG_LEVEL] = level.value
    }
    suspend fun setDefaultOutbound(outbound: XrayOutbound) = store.edit { prefs ->
        prefs[DEFAULT_OUTBOUND] = outbound.tag
    }
    suspend fun setLauncherIcon(icon: LauncherIcon) = store.edit { prefs ->
        prefs[LAUNCHER_ICON] = icon.value
    }
    suspend fun setShowAdvancedOptions(enabled: Boolean) = store.edit { prefs ->
        val wasEnabled = prefs[SHOW_ADVANCED_OPTIONS] ?: false
        if (enabled) {
            prefs[XRAY_LOG_LEVEL] = prefs[LAST_XRAY_LOG_LEVEL] ?: XrayLogLevel.default.value
        } else {
            if (wasEnabled) {
                prefs[LAST_XRAY_LOG_LEVEL] = XrayLogLevel.fromValue(prefs[XRAY_LOG_LEVEL]).value
            }
            prefs[XRAY_LOG_LEVEL] = XrayLogLevel.None.value
        }
        prefs[SHOW_ADVANCED_OPTIONS] = enabled
    }
    suspend fun setAppSpecificServerNoteShown(shown: Boolean) = store.edit { prefs ->
        prefs[APP_SPECIFIC_SERVER_NOTE_SHOWN] = shown
    }
    suspend fun setRoutingPolicyControl(policy: RoutingPolicyControl) = store.edit { prefs ->
        prefs[ROUTING_POLICY_CONTROL] = policy.value
    }
    suspend fun setUseRootService(enabled: Boolean) = store.edit { prefs ->
        prefs[USE_ROOT_SERVICE] = enabled
    }
    suspend fun setNotificationEnabled(enabled: Boolean) = store.edit { prefs ->
        prefs[NOTIFICATION_ENABLED] = enabled
    }
    suspend fun setNotificationUpdateIntervalMs(intervalMs: Int) = store.edit { prefs ->
        prefs[NOTIFICATION_UPDATE_INTERVAL_MS] = intervalMs.coerceIn(
            NotificationSettings.MIN_UPDATE_INTERVAL_MS,
            NotificationSettings.MAX_UPDATE_INTERVAL_MS,
        )
    }
    suspend fun setNotificationStyle(style: NotificationStyle) = store.edit { prefs ->
        prefs[NOTIFICATION_STYLE] = style.name
    }
    suspend fun setNotificationShowTrafficSpeed(enabled: Boolean) = store.edit { prefs ->
        prefs[NOTIFICATION_SHOW_TRAFFIC_SPEED] = enabled
    }
    suspend fun setNotificationShowRamUsage(enabled: Boolean) = store.edit { prefs ->
        prefs[NOTIFICATION_SHOW_RAM_USAGE] = enabled
    }
    suspend fun setNotificationShowConnectionCount(enabled: Boolean) = store.edit { prefs ->
        prefs[NOTIFICATION_SHOW_CONNECTION_COUNT] = enabled
    }
    suspend fun setNotificationFieldOrder(fields: List<NotificationField>) = store.edit { prefs ->
        prefs[NOTIFICATION_FIELD_ORDER] = encodeNotificationFieldOrder(fields)
    }
    suspend fun setSubscriptionSendHardwareId(enabled: Boolean) = store.edit { prefs ->
        prefs[SUBSCRIPTION_SEND_HWID] = enabled
    }
    suspend fun setAppUpdateChecksEnabled(enabled: Boolean) = store.edit { prefs ->
        prefs[APP_UPDATE_CHECKS_ENABLED] = enabled
    }
    suspend fun setGeoipUrl(url: String) = store.edit { prefs ->
        prefs.remove(LEGACY_GEO_DATA_BASE_URL)
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) prefs.remove(GEOIP_URL) else prefs[GEOIP_URL] = trimmedUrl
    }
    suspend fun setGeositeUrl(url: String) = store.edit { prefs ->
        prefs.remove(LEGACY_GEO_DATA_BASE_URL)
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) prefs.remove(GEOSITE_URL) else prefs[GEOSITE_URL] = trimmedUrl
    }
    suspend fun setLatencyCheckUrl(url: String) = store.edit { prefs ->
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) prefs.remove(LATENCY_CHECK_URL) else prefs[LATENCY_CHECK_URL] = trimmedUrl
    }
    suspend fun setDefaultPingMethod(method: PingMethod) = store.edit { prefs ->
        prefs[DEFAULT_PING_METHOD] = method.value
    }
    suspend fun setSortOutboundsByLatency(enabled: Boolean) = store.edit { prefs ->
        prefs[SORT_OUTBOUNDS_BY_LATENCY] = enabled
    }
    suspend fun setRoutingRule(rule: RoutingRule) = store.edit { prefs ->
        val updatedRules = decodeRoutingRules(
            rulesEncoded = prefs[ROUTING_RULES],
            rulesVersion = prefs[ROUTING_RULES_VERSION],
            statesEncoded = prefs[ROUTING_RULE_STATES],
            deletedDefaultRuleIds = prefs[DELETED_DEFAULT_ROUTING_RULE_IDS].orEmpty(),
        ).map { existing ->
            if (existing.id == rule.id) rule else existing
        }
        prefs[ROUTING_RULES] = encodeRoutingRules(updatedRules)
        prefs[ROUTING_RULES_VERSION] = CURRENT_ROUTING_RULES_VERSION
        prefs[DELETED_DEFAULT_ROUTING_RULE_IDS] = deletedDefaultRuleIds(updatedRules)
        prefs.remove(ROUTING_RULE_STATES)
    }
    suspend fun setRoutingRules(rules: List<RoutingRule>) = store.edit { prefs ->
        prefs[ROUTING_RULES] = encodeRoutingRules(rules)
        prefs[ROUTING_RULES_VERSION] = CURRENT_ROUTING_RULES_VERSION
        prefs[DELETED_DEFAULT_ROUTING_RULE_IDS] = deletedDefaultRuleIds(rules)
        prefs.remove(ROUTING_RULE_STATES)
    }

    suspend fun setSubscriptionRouting(routing: SubscriptionRouting?) = store.edit { prefs ->
        val normalized = routing?.normalized()
        val rules = normalized?.rules.orEmpty()
        prefs[ROUTING_RULES] = encodeRoutingRules(rules)
        prefs[ROUTING_RULES_VERSION] = CURRENT_ROUTING_RULES_VERSION
        prefs[DELETED_DEFAULT_ROUTING_RULE_IDS] = deletedDefaultRuleIds(rules)
        prefs.remove(ROUTING_RULE_STATES)
        prefs[ROUTING_DOMAIN_STRATEGY] = normalized?.domainStrategy ?: SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY
        normalized?.domainMatcher?.let { prefs[ROUTING_DOMAIN_MATCHER] = it }
            ?: prefs.remove(ROUTING_DOMAIN_MATCHER)
        normalized?.fallbackOutboundTag?.let { prefs[ROUTING_FALLBACK_OUTBOUND] = it }
            ?: prefs.remove(ROUTING_FALLBACK_OUTBOUND)
    }

    suspend fun getAllAsMap(): Map<String, String> {
        val prefs = store.data.first()
        return prefs.asMap().entries.associate { (k, v) -> k.name to v.toString() }
    }

    suspend fun restoreFromMap(map: Map<String, String>, sourceBackupVersion: Int? = null) {
        store.edit { prefs ->
            prefs.clear()
            map["tun_name"]?.let { prefs[TUN_NAME] = it }
            map["dns_servers"]?.let { prefs[DNS_SERVERS] = it }
            map["domestic_dns_servers"]?.let { prefs[DOMESTIC_DNS_SERVERS] = it }
            map["fwmark"]?.let { prefs[FWMARK] = it.toIntOrNull() ?: 255 }
            map["route_table"]?.let { prefs[ROUTE_TABLE] = it.toIntOrNull() ?: 100 }
            map["xray_buffer_size_kib"]
                ?.toIntOrNull()
                ?.let(XrayRuntimeSettings::normalizeXrayBufferSizeKiB)
                ?.let { prefs[XRAY_BUFFER_SIZE_KIB] = it }
            map["tun_mtu"]
                ?.toIntOrNull()
                ?.let(XrayRuntimeSettings::normalizeTunMtu)
                ?.let { prefs[TUN_MTU] = it }
            map["xray_memory_restart_threshold_mib"]
                ?.toIntOrNull()
                ?.let(XrayRuntimeSettings::normalizeXrayMemoryRestartThresholdMiB)
                ?.let { prefs[XRAY_MEMORY_RESTART_THRESHOLD_MIB] = it }
            map["passive_health_monitoring_enabled"]
                ?.toBooleanStrictOrNull()
                ?.let { prefs[PASSIVE_HEALTH_MONITORING_ENABLED] = it }
            map["auto_connect"]?.let { prefs[AUTO_CONNECT] = it.toBooleanStrictOrNull() ?: false }
            map["bypass_lan"]?.toBooleanStrictOrNull()?.let { prefs[BYPASS_LAN] = it }
            map["allow_ipv6"]?.toBooleanStrictOrNull()?.let { prefs[ALLOW_IPV6] = it }
            map["last_server_id"]?.let { prefs[LAST_SERVER_ID] = it.toLongOrNull() ?: -1L }
            val showAdvancedOptions = map["show_advanced_options"]?.toBooleanStrictOrNull()
            val lastXrayLogLevelValue = map["last_xray_log_level"] ?: map["xray_log_level"]
            lastXrayLogLevelValue?.let { value ->
                val lastXrayLogLevel = XrayLogLevel.fromValue(value)
                prefs[LAST_XRAY_LOG_LEVEL] = lastXrayLogLevel.value
                prefs[XRAY_LOG_LEVEL] = if (showAdvancedOptions == true) {
                    lastXrayLogLevel.value
                } else {
                    XrayLogLevel.None.value
                }
            }
            map["default_outbound"]?.let { prefs[DEFAULT_OUTBOUND] = XrayOutbound.fromTag(it).tag }
            map["launcher_icon"]?.let { prefs[LAUNCHER_ICON] = LauncherIcon.fromValue(it).value }
            showAdvancedOptions?.let { prefs[SHOW_ADVANCED_OPTIONS] = it }
            map["app_specific_server_note_shown"]
                ?.toBooleanStrictOrNull()
                ?.let { prefs[APP_SPECIFIC_SERVER_NOTE_SHOWN] = it }
            map["routing_policy_control"]
                ?.let { prefs[ROUTING_POLICY_CONTROL] = RoutingPolicyControl.fromValue(it).value }
            map["use_root_service"]?.toBooleanStrictOrNull()?.let { prefs[USE_ROOT_SERVICE] = it }
            map["notification_enabled"]?.toBooleanStrictOrNull()?.let { prefs[NOTIFICATION_ENABLED] = it }
            map["notification_update_interval_ms"]
                ?.toIntOrNull()
                ?.coerceIn(NotificationSettings.MIN_UPDATE_INTERVAL_MS, NotificationSettings.MAX_UPDATE_INTERVAL_MS)
                ?.let { prefs[NOTIFICATION_UPDATE_INTERVAL_MS] = it }
            map["notification_style"]?.let { prefs[NOTIFICATION_STYLE] = NotificationStyle.fromValue(it).name }
            map["notification_show_traffic_speed"]
                ?.toBooleanStrictOrNull()
                ?.let { prefs[NOTIFICATION_SHOW_TRAFFIC_SPEED] = it }
            map["notification_show_ram_usage"]
                ?.toBooleanStrictOrNull()
                ?.let { prefs[NOTIFICATION_SHOW_RAM_USAGE] = it }
            map["notification_show_connection_count"]
                ?.toBooleanStrictOrNull()
                ?.let { prefs[NOTIFICATION_SHOW_CONNECTION_COUNT] = it }
            map["notification_field_order"]?.let { encoded ->
                prefs[NOTIFICATION_FIELD_ORDER] = encodeNotificationFieldOrder(decodeNotificationFieldOrder(encoded))
            }
            map["subscription_send_hwid"]?.toBooleanStrictOrNull()?.let { prefs[SUBSCRIPTION_SEND_HWID] = it }
            map["subscription_prefer_json"]?.toBooleanStrictOrNull()?.let { prefs[SUBSCRIPTION_PREFER_JSON] = it }
            map["app_update_checks_enabled"]?.toBooleanStrictOrNull()?.let { prefs[APP_UPDATE_CHECKS_ENABLED] = it }
            map["geoip_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOIP_URL] = it }
            map["geosite_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOSITE_URL] = it }
            map["latency_check_url"]?.takeIf { it.isNotBlank() }?.let { prefs[LATENCY_CHECK_URL] = it }
            map["default_ping_method"]?.let { prefs[DEFAULT_PING_METHOD] = PingMethod.fromValue(it).value }
            map["sort_outbounds_by_latency"]?.toBooleanStrictOrNull()?.let { prefs[SORT_OUTBOUNDS_BY_LATENCY] = it }
            map["routing_rules"]?.takeIf { it.isNotBlank() }?.let { prefs[ROUTING_RULES] = it }
            map["routing_rules_version"]?.toIntOrNull()?.let { prefs[ROUTING_RULES_VERSION] = it }
            map["routing_rule_states"]?.takeIf { it.isNotBlank() }?.let { prefs[ROUTING_RULE_STATES] = it }
            map["routing_domain_strategy"]
                ?.let(SubscriptionRouting::normalizeDomainStrategy)
                ?.let { prefs[ROUTING_DOMAIN_STRATEGY] = it }
            map["routing_domain_matcher"]
                ?.let(SubscriptionRouting::normalizeDomainMatcher)
                ?.let { prefs[ROUTING_DOMAIN_MATCHER] = it }
            map["routing_fallback_outbound"]
                ?.let(SubscriptionRouting::normalizeFallbackOutboundTag)
                ?.let { prefs[ROUTING_FALLBACK_OUTBOUND] = it }
            map["deleted_default_routing_rule_ids"]
                ?.split(",")
                ?.map { it.trim().trim('[', ']') }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?.let { prefs[DELETED_DEFAULT_ROUTING_RULE_IDS] = it }
            map["geo_data_base_url"]?.takeIf { it.isNotBlank() }?.let { legacyBaseUrl ->
                prefs[GEOIP_URL] = appendLegacyFileName(legacyBaseUrl, "geoip.dat")
                prefs[GEOSITE_URL] = appendLegacyFileName(legacyBaseUrl, "geosite.dat")
            }
            applySettingsDefaultChanges(
                preferences = prefs,
                sourceRevision = settingsDefaultsRevisionFromBackup(map, sourceBackupVersion),
            )
            normalizeStoredDnsSettings(prefs)
        }
    }

    private fun appendLegacyFileName(baseUrl: String, fileName: String): String = "${baseUrl.trim().trimEnd('/')}/$fileName"

    private fun decodeNotificationFieldOrder(encoded: String?): List<NotificationField> {
        val savedFields = encoded
            ?.split(',')
            ?.mapNotNull { value ->
                NotificationField.entries.firstOrNull { it.name == value.trim() }
            }
            .orEmpty()
        return (savedFields + NotificationField.entries).distinct()
    }

    private fun encodeNotificationFieldOrder(fields: List<NotificationField>): String = (fields + NotificationField.entries)
        .distinct()
        .joinToString(",") { it.name }

    private fun decodeRoutingRuleStates(encoded: String?): Map<String, Boolean> = runCatching {
        if (encoded.isNullOrBlank()) {
            emptyMap()
        } else {
            json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer()), encoded)
        }
    }.getOrDefault(emptyMap())

    private fun encodeRoutingRuleStates(states: Map<String, Boolean>): String = json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer()), states)

    private fun decodeRoutingRules(
        rulesEncoded: String?,
        rulesVersion: Int?,
        statesEncoded: String?,
        deletedDefaultRuleIds: Set<String>,
    ): List<RoutingRule> {
        val savedRules = runCatching {
            if (rulesEncoded.isNullOrBlank() || rulesVersion != CURRENT_ROUTING_RULES_VERSION) {
                null
            } else {
                json.decodeFromString(ListSerializer(RoutingRule.serializer()), rulesEncoded)
            }
        }.getOrNull()

        if (savedRules != null) {
            return RoutingRuleCatalog.mergeWithDefaults(savedRules, deletedDefaultRuleIds)
        }

        val stateOverrides = decodeRoutingRuleStates(statesEncoded)
        return RoutingRuleCatalog.defaults().map { rule ->
            rule.copy(enabled = stateOverrides[rule.id] ?: rule.enabled)
        }
    }

    private fun encodeRoutingRules(rules: List<RoutingRule>): String = json.encodeToString(ListSerializer(RoutingRule.serializer()), rules)

    private fun deletedDefaultRuleIds(rules: List<RoutingRule>): Set<String> {
        val presentRuleIds = rules.mapTo(mutableSetOf()) { it.id }
        return RoutingRuleCatalog.defaultIds().filterNotTo(mutableSetOf()) { it in presentRuleIds }
    }
}
