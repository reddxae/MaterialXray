package com.material.xray.service

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class XrayLogStreamer(
    private val logFile: File,
    private val logBuffer: LogBuffer,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var offset = 0L
            while (isActive) {
                if (!logFile.exists()) {
                    offset = 0L
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                val length = logFile.length()
                if (length < offset) offset = 0L

                if (length > offset) {
                    offset = readNewLines(offset)
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun readNewLines(offset: Long): Long {
        RandomAccessFile(logFile, "r").use { file ->
            file.seek(offset)
            while (true) {
                val line = file.readUtf8Line() ?: break
                logBuffer.append(LogSource.XRAY, line)
            }
            return file.filePointer
        }
    }

    private fun RandomAccessFile.readUtf8Line(): String? = readLine()?.toByteArray(Charsets.ISO_8859_1)?.toString(Charsets.UTF_8)

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
