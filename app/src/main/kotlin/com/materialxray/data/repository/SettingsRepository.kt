package com.materialxray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.dataStore

    companion object {
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val FWMARK = intPreferencesKey("fwmark")
        val ROUTE_TABLE = intPreferencesKey("route_table")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val LAST_SERVER_ID = longPreferencesKey("last_server_id")
        val GEOIP_URL = stringPreferencesKey("geoip_url")
        val GEOSITE_URL = stringPreferencesKey("geosite_url")
        private val LEGACY_GEO_DATA_BASE_URL = stringPreferencesKey("geo_data_base_url")

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
    val lastServerId: Flow<Long> = store.data.map { it[LAST_SERVER_ID] ?: -1L }
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

    suspend fun setTunName(name: String) = store.edit { it[TUN_NAME] = name }
    suspend fun setDnsServers(servers: String) = store.edit { it[DNS_SERVERS] = servers }
    suspend fun setFwmark(mark: Int) = store.edit { it[FWMARK] = mark }
    suspend fun setRouteTable(table: Int) = store.edit { it[ROUTE_TABLE] = table }
    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[AUTO_CONNECT] = enabled }
    suspend fun setLastServerId(id: Long) = store.edit { it[LAST_SERVER_ID] = id }
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
            map["last_server_id"]?.let { prefs[LAST_SERVER_ID] = it.toLongOrNull() ?: -1L }
            map["geoip_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOIP_URL] = it }
            map["geosite_url"]?.takeIf { it.isNotBlank() }?.let { prefs[GEOSITE_URL] = it }
            map["geo_data_base_url"]?.takeIf { it.isNotBlank() }?.let { legacyBaseUrl ->
                prefs[GEOIP_URL] = appendLegacyFileName(legacyBaseUrl, "geoip.dat")
                prefs[GEOSITE_URL] = appendLegacyFileName(legacyBaseUrl, "geosite.dat")
            }
        }
    }

    private fun appendLegacyFileName(baseUrl: String, fileName: String): String =
        "${baseUrl.trim().trimEnd('/')}/$fileName"
}
