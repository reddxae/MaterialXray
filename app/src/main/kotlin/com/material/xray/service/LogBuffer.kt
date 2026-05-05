package com.material.xray.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val source: LogSource,
    val message: String,
)

enum class LogSource { APP, XRAY }

@Singleton
class LogBuffer @Inject constructor() {
    private val maxSize = 2000
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries
    private val buffer = ArrayDeque<LogEntry>(maxSize)
    private var nextId = 0L

    @Synchronized
    fun append(source: LogSource, message: String) {
        runCatching {
            when (source) {
                LogSource.APP -> Log.d("MXray", message)
                LogSource.XRAY -> Log.d("MXray.xray", message)
            }
        }

        if (buffer.size == maxSize) {
            buffer.removeFirst()
        }
        buffer.addLast(
            LogEntry(
                id = nextId++,
                source = source,
                message = message,
            )
        )
        _entries.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun formatAll(): String = _entries.value.joinToString("\n") { entry ->
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
        "$time [${entry.source.name}] ${entry.message}"
    }
}
