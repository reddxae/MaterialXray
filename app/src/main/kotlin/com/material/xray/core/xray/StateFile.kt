package com.material.xray.core.xray

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class XrayState(
    val xrayPid: Int = -1,
    val tunName: String = "xray0",
    val serverName: String = "",
    val nftTableCreated: Boolean = false,
    val ipRulesApplied: Boolean = false,
    val appProxyServerIds: List<Long> = emptyList(),
    val routeTable: Int = 100,
    val bypassTable: Int = 101,
    val fwmark: Int = 255,
    val routeMark: Int = 100,
    val physicalInterface: String? = null,
    val physicalGateway: String? = null,
    val physicalTable: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

class StateFile(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun read(): XrayState? = runCatching {
        if (!file.exists()) return null
        json.decodeFromString<XrayState>(file.readText())
    }.getOrNull()

    fun write(state: XrayState) {
        file.writeText(json.encodeToString(state))
    }

    fun delete() {
        file.delete()
    }
}
