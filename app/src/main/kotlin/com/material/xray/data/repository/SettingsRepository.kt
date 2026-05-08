package com.material.xray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.LauncherIcon
import com.material.xray.model.XrayRuntimeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val DOMESTIC_DNS_SERVERS = stringPreferencesKey("domestic_dns_servers")
        val LATENCY_DNS_SERVERS = stringPreferencesKey("latency_dns_servers")
        val FWMARK = intPreferencesKey("fwmark")
        val ROUTE_TABLE = intPreferencesKey("route_table")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val LAST_SERVER_ID = longPreferencesKey("last_server_id")
        val GEOIP_URL = stringPreferencesKey("geoip_url")
        val GEOSITE_URL = stringPreferencesKey("geosite_url")
        val LATENCY_CHECK_URL = stringPreferencesKey("latency_check_url")
        val XRAY_LOG_LEVEL = stringPreferencesKey("xray_log_level")
        val LAST_XRAY_LOG_LEVEL = stringPreferencesKey("last_xray_log_level")
        val DEFAULT_OUTBOUND = stringPreferencesKey("default_outbound")
        val LAUNCHER_ICON = stringPreferencesKey("launcher_icon")
        val SHOW_ADVANCED_OPTIONS = booleanPreferencesKey("show_advanced_options")
        val APP_SPECIFIC_SERVER_NOTE_SHOWN = booleanPreferencesKey("app_specific_server_note_shown")
        val ROUTING_RULES = stringPreferencesKey("routing_rules")
        val ROUTING_RULES_VERSION = intPreferencesKey("routing_rules_version")
        val ROUTING_RULE_STATES = stringPreferencesKey("routing_rule_states")
        val DELETED_DEFAULT_ROUTING_RULE_IDS = stringSetPreferencesKey("deleted_default_routing_rule_ids")
        val USE_ROOT_SERVICE = booleanPreferencesKey("use_root_service")
        private val LEGACY_GEO_DATA_BASE_URL = stringPreferencesKey("geo_data_base_url")
        private const val CURRENT_ROUTING_RULES_VERSION = 2

        const val DEFAULT_GEOIP_URL =
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
        const val DEFAULT_GEOSITE_URL =
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
        const val DEFAULT_LATENCY_CHECK_URL = "https://gstatic.com/generate_204"
        const val DEFAULT_DNS_SERVERS = "1.1.1.1,1.0.0.1"
        const val DEFAULT_DOMESTIC_DNS_SERVERS = "77.88.8.8,77.88.8.1"
        const val DEFAULT_LATENCY_DNS_SERVERS = "77.88.8.8,77.88.8.1"
    }

    val tunName: Flow<String> = store.data.map { it[TUN_NAME] ?: "xray0" }
    val dnsServers: Flow<String> = store.data.map { it[DNS_SERVERS] ?: DEFAULT_DNS_SERVERS }
    val domesticDnsServers: Flow<String> = store.data.map {
        it[DOMESTIC_DNS_SERVERS] ?: DEFAULT_DOMESTIC_DNS_SERVERS
    }
    val latencyDnsServers: Flow<String> = store.data.map { it[LATENCY_DNS_SERVERS] ?: DEFAULT_LATENCY_DNS_SERVERS }
    val fwmark: Flow<Int> = store.data.map { it[FWMARK] ?: 255 }
    val routeTable: Flow<Int> = store.data.map { it[ROUTE_TABLE] ?: 100 }
    val autoConnect: Flow<Boolean> = store.data.map { it[AUTO_CONNECT] ?: false }
    val bypassLan: Flow<Boolean> = store.data.map { it[BYPASS_LAN] ?: true }
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
    val routingRules: Flow<List<RoutingRule>> = store.data.map { prefs ->
        decodeRoutingRules(
            rulesEncoded = prefs[ROUTING_RULES],
            rulesVersion = prefs[ROUTING_RULES_VERSION],
            statesEncoded = prefs[ROUTING_RULE_STATES],
            deletedDefaultRuleIds = prefs[DELETED_DEFAULT_ROUTING_RULE_IDS].orEmpty(),
        )
    }

    suspend fun runtimeSettingsSnapshot(): XrayRuntimeSettings =
        XrayRuntimeSettings(
            tunName = tunName.first(),
            fwmark = fwmark.first(),
            routeTable = routeTable.first(),
            useRootService = useRootService.first(),
            dnsServers = dnsServers.first(),
            domesticDnsServers = domesticDnsServers.first(),
            logLevel = xrayLogLevel.first(),
            defaultOutbound = defaultOutbound.first(),
            bypassLan = bypassLan.first(),
            routingRules = routingRules.first(),
        )

    suspend fun setTunName(name: String) = store.edit { it[TUN_NAME] = name }
    suspend fun setDnsServers(servers: String) = store.edit { it[DNS_SERVERS] = servers }
    suspend fun setDomesticDnsServers(servers: String) = store.edit { it[DOMESTIC_DNS_SERVERS] = servers }
    suspend fun setLatencyDnsServers(servers: String) = store.edit { it[LATENCY_DNS_SERVERS] = servers }
    suspend fun setFwmark(mark: Int) = store.edit { it[FWMARK] = mark }
    suspend fun setRouteTable(table: Int) = store.edit { it[ROUTE_TABLE] = table }
    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[AUTO_CONNECT] = enabled }
    suspend fun setBypassLan(enabled: Boolean) = store.edit { it[BYPASS_LAN] = enabled }
    suspend fun setLastServerId(id: Long) = store.edit { it[LAST_SERVER_ID] = id }
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
    suspend fun setUseRootService(enabled: Boolean) = store.edit { prefs ->
        prefs[USE_ROOT_SERVICE] = enabled
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

    suspend fun getAllAsMap(): Map<String, String> {
        val prefs = store.data.first()
        return prefs.asMap().entries.associate { (k, v) -> k.name to v.toString() }
    }

    suspend fun restoreFromMap(map: Map<String, String>) {
        store.edit { prefs ->
            prefs.clear()
            map["tun_name"]?.let { prefs[TUN_NAME] = it }
            map["dns_servers"]?.let { prefs[DNS_SERVERS] = it }
            map["domestic_dns_servers"]?.let { prefs[DOMESTIC_DNS_SERVERS] = it }
            map["latency_dns_servers"]?.let { prefs[LATENCY_DNS_SERVERS] = it }
            map["fwmark"]?.let { prefs[FWMARK] = it.toIntOrNull() ?: 255 }
            map["route_table"]?.let { prefs[ROUTE_TABLE] = it.toIntOrNull() ?: 100 }
            map["auto_connect"]?.let { prefs[AUTO_CONNECT] = it.toBooleanStrictOrNull() ?: false }
            prefs[BYPASS_LAN] = map["bypass_lan"]?.toBooleanStrictOrNull() ?: true
            map["last_server_id"]?.let { prefs[LAST_SERVER_ID] = it.toLongOrNull() ?: -1L }
            val showAdvancedOptions = map["show_advanced_options"]?.toBooleanStrictOrNull() ?: false
            val lastXrayLogLevel = XrayLogLevel.fromValue(map["last_xray_log_level"] ?: map["xray_log_level"])
            prefs[LAST_XRAY_LOG_LEVEL] = lastXrayLogLevel.value
            prefs[XRAY_LOG_LEVEL] = if (showAdvancedOptions) lastXrayLogLevel.value else XrayLogLevel.None.value
            prefs[DEFAULT_OUTBOUND] = XrayOutbound.fromTag(map["default_outbound"]).tag
            prefs[LAUNCHER_ICON] = LauncherIcon.fromValue(map["launcher_icon"]).value
            prefs[SHOW_ADVANCED_OPTIONS] = showAdvancedOptions
            prefs[APP_SPECIFIC_SERVER_NOTE_SHOWN] =
                map["app_specific_server_note_shown"]?.toBooleanStrictOrNull() ?: false
            prefs[USE_ROOT_SERVICE] = map["use_root_service"]?.toBooleanStrictOrNull() ?: false
            map["geoip_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOIP_URL] = it }
            map["geosite_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOSITE_URL] = it }
            map["latency_check_url"]?.takeIf { it.isNotBlank() }?.let { prefs[LATENCY_CHECK_URL] = it }
            map["routing_rules"]?.takeIf { it.isNotBlank() }?.let { prefs[ROUTING_RULES] = it }
            map["routing_rules_version"]?.let { prefs[ROUTING_RULES_VERSION] = it.toIntOrNull() ?: CURRENT_ROUTING_RULES_VERSION }
            map["routing_rule_states"]?.takeIf { it.isNotBlank() }?.let { prefs[ROUTING_RULE_STATES] = it }
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
        }
    }

    private fun appendLegacyFileName(baseUrl: String, fileName: String): String =
        "${baseUrl.trim().trimEnd('/')}/$fileName"

    private fun decodeRoutingRuleStates(encoded: String?): Map<String, Boolean> =
        runCatching {
            if (encoded.isNullOrBlank()) {
                emptyMap()
            } else {
                json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer()), encoded)
            }
        }.getOrDefault(emptyMap())

    private fun encodeRoutingRuleStates(states: Map<String, Boolean>): String =
        json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Boolean.serializer()), states)

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

    private fun encodeRoutingRules(rules: List<RoutingRule>): String =
        json.encodeToString(ListSerializer(RoutingRule.serializer()), rules)

    private fun deletedDefaultRuleIds(rules: List<RoutingRule>): Set<String> {
        val presentRuleIds = rules.mapTo(mutableSetOf()) { it.id }
        return RoutingRuleCatalog.defaultIds().filterNotTo(mutableSetOf()) { it in presentRuleIds }
    }
}
