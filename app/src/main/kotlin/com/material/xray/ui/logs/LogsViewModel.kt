package com.material.xray.ui.logs

import android.content.Context
import androidx.lifecycle.ViewModel
import com.material.xray.R
import com.material.xray.service.LogBuffer
import com.material.xray.service.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logBuffer: LogBuffer,
) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = logBuffer.entries

    fun clear() = logBuffer.clear()

    fun copyAll() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val label = context.getString(
            R.string.clipboard_label_logs,
            context.getString(R.string.app_name),
        )
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, logBuffer.formatAll()))
    }

    fun copyEntry(entry: LogEntry) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                context.getString(R.string.clipboard_label_log_entry),
                "$time [${entry.source.name}] ${entry.message}",
            ),
        )
    }
}
