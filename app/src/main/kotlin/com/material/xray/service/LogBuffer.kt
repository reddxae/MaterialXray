package com.material.xray.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private var appEntryCount = 0

    fun append(source: LogSource, message: String) {
        appendAll(source, listOf(message))
    }

    fun appendAll(source: LogSource, messages: List<String>) {
        if (messages.isEmpty()) return
        messages.forEach { message ->
            runCatching {
                when (source) {
                    LogSource.APP -> Log.d("MXray", message)
                    LogSource.XRAY -> Log.d("MXray.xray", message)
                }
            }
        }

        synchronized(this) {
            messages.forEach { message ->
                if (buffer.size == maxSize) {
                    val evictionIndex = if (source == LogSource.XRAY && appEntryCount <= MIN_RETAINED_APP_ENTRIES) {
                        buffer.indexOfFirst { entry -> entry.source == LogSource.XRAY }.takeIf { it >= 0 } ?: 0
                    } else {
                        0
                    }
                    if (buffer.removeAt(evictionIndex).source == LogSource.APP) appEntryCount--
                }
                buffer.addLast(
                    LogEntry(
                        id = nextId++,
                        source = source,
                        message = message,
                    ),
                )
                if (source == LogSource.APP) appEntryCount++
            }
            _entries.value = buffer.toList()
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        appEntryCount = 0
        _entries.value = emptyList()
    }

    @Synchronized
    fun clear(source: LogSource) {
        val retained = buffer.filterNot { it.source == source }
        buffer.clear()
        buffer.addAll(retained)
        appEntryCount = retained.count { it.source == LogSource.APP }
        _entries.value = buffer.toList()
    }

    fun formatAll(): String = _entries.value.joinToString("\n") { entry ->
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
        "$time [${entry.source.name}] ${entry.message}"
    }

    private companion object {
        const val MIN_RETAINED_APP_ENTRIES = 256
    }
}
