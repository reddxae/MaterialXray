package com.material.xray.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter

class RootShell {
    enum class NetworkNamespace {
        CURRENT,
        INIT,
    }

    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private var stderr: BufferedReader? = null
    private var defaultNamespace: NetworkNamespace = NetworkNamespace.CURRENT
    private val mutex = Mutex()

    data class Result(
        val exitCode: Int,
        val output: String,
        val error: String,
    ) {
        val isSuccess get() = exitCode == 0
    }

    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            openInternal()
        }
    }

    suspend fun execute(
        command: String,
        namespace: NetworkNamespace = defaultNamespace,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (process == null && !openInternal()) {
                return@withContext Result(-1, "", "Root shell not available")
            }
            executeInternal(command, namespace, timeoutMs)
        }
    }

    fun defaultNetworkNamespace(): NetworkNamespace = defaultNamespace

    private fun openInternal(): Boolean {
        if (process != null) return true
        return runCatching {
            val p = ProcessBuilder("su").redirectErrorStream(false).start()
            process = p
            stdin = OutputStreamWriter(p.outputStream)
            stdout = p.inputStream.bufferedReader()
            stderr = p.errorStream.bufferedReader()

            val isRoot = executeInternal("id -u", NetworkNamespace.CURRENT).output.trim() == "0"
            if (!isRoot) {
                close()
                return false
            }

            defaultNamespace = if (executeInternal("nsenter -t 1 -n -- true", NetworkNamespace.CURRENT).isSuccess) {
                NetworkNamespace.INIT
            } else {
                NetworkNamespace.CURRENT
            }
            true
        }.getOrElse {
            close()
            false
        }
    }

    private fun executeInternal(
        command: String,
        namespace: NetworkNamespace,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): Result {
        val writer = stdin ?: return Result(-1, "", "Shell closed")
        val reader = stdout ?: return Result(-1, "", "Shell closed")

        val marker = "XRAY_CMD_DONE_${System.nanoTime()}"
        val exitMarker = "XRAY_EXIT_${System.nanoTime()}"
        val wrappedCommand = wrapCommand(command, namespace)

        writer.write("$wrappedCommand\n")
        writer.write("__xray_status=${'$'}?; printf '\\n%s%s\\n' '$exitMarker' \"${'$'}__xray_status\"\n")
        writer.write("printf '%s\\n' '$marker'\n")
        writer.flush()

        val outputLines = mutableListOf<String>()
        var exitCode = -1
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI

        while (true) {
            if (!reader.ready()) {
                if (System.nanoTime() >= deadline) {
                    close()
                    return Result(-1, outputLines.joinToString("\n"), "Root command timed out after ${timeoutMs}ms: $command")
                }
                if (process?.isAlive == false) {
                    close()
                    return Result(-1, outputLines.joinToString("\n"), "Root shell exited while running: $command")
                }
                Thread.sleep(COMMAND_POLL_INTERVAL_MS)
                continue
            }

            val line = reader.readLine() ?: break
            if (line == marker) break
            if (line.startsWith(exitMarker)) {
                exitCode = line.removePrefix(exitMarker).toIntOrNull() ?: -1
            } else {
                outputLines.add(line)
            }
        }

        val errorOutput = buildString {
            val errReader = stderr ?: return@buildString
            while (errReader.ready()) {
                append(errReader.readLine())
                append('\n')
            }
        }

        return Result(exitCode, outputLines.joinToString("\n"), errorOutput.trimEnd())
    }

    private fun wrapCommand(command: String, namespace: NetworkNamespace): String = when (namespace) {
        NetworkNamespace.CURRENT -> command
        NetworkNamespace.INIT -> "nsenter -t 1 -n -- sh -lc ${shellQuote(command)}"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun close() {
        runCatching {
            stdin?.write("exit\n")
            stdin?.flush()
        }
        runCatching { process?.destroy() }
        process = null
        stdin = null
        stdout = null
        stderr = null
        defaultNamespace = NetworkNamespace.CURRENT
    }

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        const val COMMAND_POLL_INTERVAL_MS = 10L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
