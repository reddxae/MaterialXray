package com.material.xray.service

import android.os.FileObserver
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class XrayLogStreamer(
    private val logFile: File,
    private val logBuffer: LogBuffer,
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val resetRequested = AtomicBoolean(false)
    private val catchUpLimitRequested = AtomicBoolean(false)
    private var job: Job? = null
    private var observer: FileObserver? = null
    private var offset = 0L
    private var fileInode: Long? = null
    private var initialized = false
    private var closed = false

    @Volatile
    private var active = false

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (closed || active) return

        active = true
        catchUpLimitRequested.set(true)
        if (job?.isActive != true) {
            job = scope.launch(Dispatchers.IO) { processSignals() }
        }
        observer?.stopWatching()
        observer = createObserver().also(FileObserver::startWatching)
        signals.trySend(Unit)
    }

    @Synchronized
    fun stop() {
        active = false
        observer?.stopWatching()
        observer = null
    }

    @Synchronized
    fun close() {
        stop()
        closed = true
        job?.cancel()
        job = null
    }

    private suspend fun processSignals() {
        while (currentCoroutineContext().isActive) {
            signals.receive()
            if (!active) continue
            try {
                readAvailableLines(
                    forceReset = resetRequested.getAndSet(false),
                    limitCatchUp = catchUpLimitRequested.getAndSet(false),
                )
            } catch (_: IOException) {
                resetRequested.set(true)
            } catch (_: SecurityException) {
                resetRequested.set(true)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createObserver(): FileObserver {
        val parent = requireNotNull(logFile.parentFile) { "Xray log file must have a parent directory" }
        return object : FileObserver(parent.absolutePath, WATCH_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path != logFile.name) return
                if (event and RESET_EVENTS != 0) resetRequested.set(true)
                signals.trySend(Unit)
            }
        }
    }

    private fun readAvailableLines(forceReset: Boolean, limitCatchUp: Boolean) {
        val currentInode = logFile.inodeOrNull()
        val length = logFile.length().takeIf { logFile.isFile } ?: 0L
        val firstInitialization = !initialized
        val shouldReset = forceReset || firstInitialization || currentInode != fileInode || length < offset
        if (shouldReset) {
            if (firstInitialization) logBuffer.clear(LogSource.XRAY)
            offset = if (length > 0L) findLogTailOffset(logFile, LogBuffer.XRAY_TAIL_SIZE) else 0L
            fileInode = currentInode
            initialized = true
        } else if (limitCatchUp && length > offset) {
            offset = maxOf(offset, findLogTailOffset(logFile, LogBuffer.XRAY_TAIL_SIZE))
        }
        if (!active || length <= offset) return

        val result = readCompleteLogLines(logFile, offset)
        if (!active) return
        result.lines.chunked(LOG_BATCH_SIZE).forEach { messages ->
            logBuffer.appendAll(LogSource.XRAY, messages)
        }
        offset = result.nextOffset
    }

    private companion object {
        const val WATCH_EVENTS = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM
        const val RESET_EVENTS = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE or
            FileObserver.MOVED_FROM
        const val LOG_BATCH_SIZE = 256
    }
}

internal data class LogReadResult(
    val lines: List<String>,
    val nextOffset: Long,
)

internal fun readCompleteLogLines(file: File, startOffset: Long): LogReadResult {
    require(startOffset >= 0L) { "startOffset must not be negative" }
    FileInputStream(file).use { input ->
        val readableLength = input.channel.size()
        if (startOffset >= readableLength) return LogReadResult(emptyList(), 0L)
        input.channel.position(startOffset)

        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        val block = ByteArray(LOG_READ_BLOCK_SIZE)
        var consumed = 0L
        var committedOffset = startOffset
        while (startOffset + consumed < readableLength) {
            val remaining = (readableLength - startOffset - consumed).coerceAtMost(block.size.toLong()).toInt()
            val read = input.read(block, 0, remaining)
            if (read <= 0) break
            for (index in 0 until read) {
                consumed++
                val byte = block[index]
                if (byte == '\n'.code.toByte()) {
                    lines += line.toUtf8String()
                    line.reset()
                    committedOffset = startOffset + consumed
                } else {
                    line.write(byte.toInt())
                }
            }
        }
        return LogReadResult(lines, committedOffset)
    }
}

internal fun findLogTailOffset(file: File, maxLines: Int): Long {
    require(maxLines > 0) { "maxLines must be positive" }
    RandomAccessFile(file, "r").use { input ->
        var position = input.length()
        var newlineCount = if (position > 0L) {
            input.seek(position - 1L)
            if (input.readByte() == '\n'.code.toByte()) 0 else 1
        } else {
            0
        }
        val block = ByteArray(TAIL_SCAN_BLOCK_SIZE)
        while (position > 0L) {
            val blockStart = (position - block.size).coerceAtLeast(0L)
            val blockSize = (position - blockStart).toInt()
            input.seek(blockStart)
            input.readFully(block, 0, blockSize)
            for (index in blockSize - 1 downTo 0) {
                if (block[index] == '\n'.code.toByte() && ++newlineCount > maxLines) {
                    return blockStart + index + 1L
                }
            }
            position = blockStart
        }
    }
    return 0L
}

private fun ByteArrayOutputStream.toUtf8String(): String {
    val bytes = toByteArray()
    val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
    return String(bytes, 0, length, Charsets.UTF_8)
}

private fun File.inodeOrNull(): Long? = runCatching { Os.stat(absolutePath).st_ino }.getOrNull()

private const val LOG_READ_BLOCK_SIZE = 8 * 1024
private const val TAIL_SCAN_BLOCK_SIZE = 8 * 1024
