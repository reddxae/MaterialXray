package com.material.xray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayLogLevel
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
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val FWMARK = intPreferencesKey("fwmark")
        val ROUTE_TABLE = intPreferencesKey("route_table")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val LAST_SERVER_ID = longPreferencesKey("last_server_id")
        val GEOIP_URL = stringPreferencesKey("geoip_url")
        val GEOSITE_URL = stringPreferencesKey("geosite_url")
        val XRAY_LOG_LEVEL = stringPreferencesKey("xray_log_level")
        val DEFAULT_OUTBOUND = stringPreferencesKey("default_outbound")
        val ROUTING_RULES = stringPreferencesKey("routing_rules")
        val ROUTING_RULES_VERSION = intPreferencesKey("routing_rules_version")
        val ROUTING_RULE_STATES = stringPreferencesKey("routing_rule_states")
        private val LEGACY_GEO_DATA_BASE_URL = stringPreferencesKey("geo_data_base_url")
        private const val CURRENT_ROUTING_RULES_VERSION = 2

        const val DEFAULT_GEOIP_URL =
            "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
        const val DEFAULT_GEOSITE_URL =
            "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
    }

    val tunName: Flow<String> = store.data.map { it[TUN_NAME] ?: "xray0" }
    val dnsServers: Flow<String> = store.data.map { it[DNS_SERVERS] ?: "1.1.1.1,8.8.8.8" }
    val fwmark: Flow<Int> = store.data.map { it[FWMARK] ?: 255 }
    val routeTable: Flow<Int> = store.data.map { it[ROUTE_TABLE] ?: 100 }
    val autoConnect: Flow<Boolean> = store.data.map { it[AUTO_CONNECT] ?: false }
    val bypassLan: Flow<Boolean> = store.data.map { it[BYPASS_LAN] ?: true }
    val lastServerId: Flow<Long> = store.data.map { it[LAST_SERVER_ID] ?: -1L }
    val xrayLogLevel: Flow<XrayLogLevel> = store.data.map { prefs ->
        XrayLogLevel.fromValue(prefs[XRAY_LOG_LEVEL])
    }
    val defaultOutbound: Flow<XrayOutbound> = store.data.map { prefs ->
        XrayOutbound.fromTag(prefs[DEFAULT_OUTBOUND])
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
    val routingRules: Flow<List<RoutingRule>> = store.data.map { prefs ->
        decodeRoutingRules(
            rulesEncoded = prefs[ROUTING_RULES],
            rulesVersion = prefs[ROUTING_RULES_VERSION],
            statesEncoded = prefs[ROUTING_RULE_STATES],
        )
    }

    suspend fun setTunName(name: String) = store.edit { it[TUN_NAME] = name }
    suspend fun setDnsServers(servers: String) = store.edit { it[DNS_SERVERS] = servers }
    suspend fun setFwmark(mark: Int) = store.edit { it[FWMARK] = mark }
    suspend fun setRouteTable(table: Int) = store.edit { it[ROUTE_TABLE] = table }
    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[AUTO_CONNECT] = enabled }
    suspend fun setBypassLan(enabled: Boolean) = store.edit { it[BYPASS_LAN] = enabled }
    suspend fun setLastServerId(id: Long) = store.edit { it[LAST_SERVER_ID] = id }
    suspend fun setXrayLogLevel(level: XrayLogLevel) = store.edit { prefs ->
        prefs[XRAY_LOG_LEVEL] = level.value
    }
    suspend fun setDefaultOutbound(outbound: XrayOutbound) = store.edit { prefs ->
        prefs[DEFAULT_OUTBOUND] = outbound.tag
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
    suspend fun setRoutingRule(rule: RoutingRule) = store.edit { prefs ->
        val updatedRules = decodeRoutingRules(
            rulesEncoded = prefs[ROUTING_RULES],
            rulesVersion = prefs[ROUTING_RULES_VERSION],
            statesEncoded = prefs[ROUTING_RULE_STATES],
        ).map { existing ->
            if (existing.id == rule.id) rule else existing
        }
        prefs[ROUTING_RULES] = encodeRoutingRules(updatedRules)
        prefs[ROUTING_RULES_VERSION] = CURRENT_ROUTING_RULES_VERSION
        prefs.remove(ROUTING_RULE_STATES)
    }
    suspend fun setRoutingRules(rules: List<RoutingRule>) = store.edit { prefs ->
        prefs[ROUTING_RULES] = encodeRoutingRules(rules)
        prefs[ROUTING_RULES_VERSION] = CURRENT_ROUTING_RULES_VERSION
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
            map["fwmark"]?.let { prefs[FWMARK] = it.toIntOrNull() ?: 255 }
            map["route_table"]?.let { prefs[ROUTE_TABLE] = it.toIntOrNull() ?: 100 }
            map["auto_connect"]?.let { prefs[AUTO_CONNECT] = it.toBooleanStrictOrNull() ?: false }
            prefs[BYPASS_LAN] = map["bypass_lan"]?.toBooleanStrictOrNull() ?: true
            map["last_server_id"]?.let { prefs[LAST_SERVER_ID] = it.toLongOrNull() ?: -1L }
            prefs[XRAY_LOG_LEVEL] = XrayLogLevel.fromValue(map["xray_log_level"]).value
            prefs[DEFAULT_OUTBOUND] = XrayOutbound.fromTag(map["default_outbound"]).tag
            map["geoip_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOIP_URL] = it }
            map["geosite_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOSITE_URL] = it }
            map["routing_rules"]?.takeIf { it.isNotBlank() }?.let { prefs[ROUTING_RULES] = it }
            map["routing_rules_version"]?.let { prefs[ROUTING_RULES_VERSION] = it.toIntOrNull() ?: CURRENT_ROUTING_RULES_VERSION }
            map["routing_rule_states"]?.takeIf { it.isNotBlank() }?.let { prefs[ROUTING_RULE_STATES] = it }
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
    ): List<RoutingRule> {
        val savedRules = runCatching {
            if (rulesEncoded.isNullOrBlank() || rulesVersion != CURRENT_ROUTING_RULES_VERSION) {
                null
            } else {
                json.decodeFromString(ListSerializer(RoutingRule.serializer()), rulesEncoded)
            }
        }.getOrNull()

        if (savedRules != null) {
            return RoutingRuleCatalog.mergeWithDefaults(savedRules)
        }

        val stateOverrides = decodeRoutingRuleStates(statesEncoded)
        return RoutingRuleCatalog.defaults().map { rule ->
            rule.copy(enabled = stateOverrides[rule.id] ?: rule.enabled)
        }
    }

    private fun encodeRoutingRules(rules: List<RoutingRule>): String =
        json.encodeToString(ListSerializer(RoutingRule.serializer()), rules)
}
