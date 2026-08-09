package com.material.xray.core.xray

import android.content.Context
import android.util.AtomicFile
import android.util.Log
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

/**
 * Outcome of reading the persisted runtime state.
 *
 * [Absent] and [Unreadable] are deliberately distinct: a file that exists but cannot be parsed may
 * still describe a live root-managed runtime, so callers must not treat it as proof that nothing
 * is running.
 */
sealed interface XrayStateReadResult {
    data class Present(val state: XrayState) : XrayStateReadResult
    data object Absent : XrayStateReadResult
    data object Unreadable : XrayStateReadResult
}

class StateFile(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "state.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun readResult(): XrayStateReadResult {
        if (!file.baseFile.exists()) return XrayStateReadResult.Absent
        return runCatching {
            val encoded = file.openRead().bufferedReader().use { it.readText() }
            json.decodeFromString<XrayState>(encoded)
        }.fold(
            onSuccess = { XrayStateReadResult.Present(it) },
            onFailure = { error ->
                Log.w(TAG, "state.json exists but could not be read", error)
                XrayStateReadResult.Unreadable
            },
        )
    }

    fun read(): XrayState? = (readResult() as? XrayStateReadResult.Present)?.state

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

    private companion object {
        private const val TAG = "StateFile"
    }
}
