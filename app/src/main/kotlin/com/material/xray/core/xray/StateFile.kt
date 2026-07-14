package com.material.xray.core.xray

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class XrayState(
    val xrayPid: Int = -1,
    val xrayApiPort: Int? = null,
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
    private val file = AtomicFile(File(context.filesDir, "state.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun read(): XrayState? = runCatching {
        if (!file.baseFile.exists()) return null
        val encoded = file.openRead().bufferedReader().use { it.readText() }
        json.decodeFromString<XrayState>(encoded)
    }.getOrNull()

    fun write(state: XrayState) {
        val output = file.startWrite()
        var committed = false
        try {
            output.write(json.encodeToString(state).toByteArray())
            file.finishWrite(output)
            committed = true
        } finally {
            if (!committed) file.failWrite(output)
        }
    }

    fun delete() {
        file.delete()
    }
}
