package com.materialxray.core.xray

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class XrayState(
    val xrayPid: Int = -1,
    val tunName: String = "xray0",
    val nftTableCreated: Boolean = false,
    val ipRulesApplied: Boolean = false,
    val appProxyServerIds: List<Long> = emptyList(),
    val routeTable: Int = 100,
    val bypassTable: Int = 101,
    val fwmark: Int = 255,
    val routeMark: Int = 100,
    val timestamp: Long = System.currentTimeMillis(),
)

class StateFile(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

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
