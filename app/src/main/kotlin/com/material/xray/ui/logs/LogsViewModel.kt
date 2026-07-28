package com.material.xray.ui.logs

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.service.LogBuffer
import com.material.xray.service.LogEntry
import com.material.xray.service.XRAY_LOG_FILE_NAME
import com.material.xray.service.XrayLogStreamer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal const val LOG_EXPORT_FILE_NAME = "material-xray-logs.txt"
private const val LOG_EXPORT_DIRECTORY = "logs"

@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logBuffer: LogBuffer,
) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = logBuffer.entries
    private val xrayLogStreamer = XrayLogStreamer(context.filesDir.resolve(XRAY_LOG_FILE_NAME), logBuffer)

    fun onVisible() = xrayLogStreamer.start(viewModelScope)

    fun onHidden() = xrayLogStreamer.stop()

    fun clear() = logBuffer.clear()

    fun copyAll() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val label = context.localizedString(
            R.string.clipboard_label_logs,
            context.localizedString(R.string.app_name),
        )
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, logBuffer.formatAll()))
    }

    fun copyEntry(entry: LogEntry) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                context.localizedString(R.string.clipboard_label_log_entry),
                "$time [${entry.source.name}] ${entry.message}",
            ),
        )
    }

    suspend fun saveLogs(destination: Uri) = withContext(Dispatchers.IO) {
        val outputStream = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("Unable to open the selected log file")
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(logBuffer.formatAll())
        }
    }

    suspend fun createShareFile(): Uri = withContext(Dispatchers.IO) {
        val exportDirectory = File(context.cacheDir, LOG_EXPORT_DIRECTORY)
        if (!exportDirectory.isDirectory && !exportDirectory.mkdirs()) {
            throw IOException("Unable to create the log export directory")
        }

        val exportFile = File(exportDirectory, LOG_EXPORT_FILE_NAME)
        exportFile.writeText(logBuffer.formatAll(), Charsets.UTF_8)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile,
        )
    }

    override fun onCleared() {
        xrayLogStreamer.close()
        super.onCleared()
    }
}
