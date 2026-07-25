package com.material.xray.core.root

import android.os.Process as AndroidProcess
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * App-scoped root shell. Service recreation deliberately keeps this process alive;
 * [close] is reserved for terminal app-process shutdown and cancellation recovery.
 */
class RootShell(
    private val appProcessId: Int = AndroidProcess.myPid(),
) {
    enum class NetworkNamespace {
        CURRENT,
        INIT,
    }

    @Volatile private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private var stderr: BufferedReader? = null
    private var stdoutEvents = LinkedBlockingQueue<StreamEvent>()
    private var stderrEvents = LinkedBlockingQueue<StreamEvent>()
    private var stdoutPump: Thread? = null
    private var stderrPump: Thread? = null
    private var directNamespaces: Set<NetworkNamespace> = emptySet()
    private var appNamespaceAvailable = false
    private var initNamespaceAvailable = false

    @Volatile private var defaultNamespace: NetworkNamespace = NetworkNamespace.INIT
    private val lock = ReentrantLock()
    private val closeGeneration = AtomicLong()

    data class Result(
        val exitCode: Int,
        val output: String,
        val error: String,
    ) {
        val isSuccess get() = exitCode == 0
    }

    suspend fun open(requiredNamespace: NetworkNamespace? = null): Boolean = withContext(Dispatchers.IO) {
        val expectedGeneration = closeGeneration.get()
        runInterruptible {
            withInterruptibleLock {
                if (closeGeneration.get() != expectedGeneration) return@withInterruptibleLock false
                val opened = openInternal(expectedGeneration)
                if (closeGeneration.get() != expectedGeneration) {
                    closeInternal()
                    return@withInterruptibleLock false
                }
                opened && (requiredNamespace == null || isNamespaceAvailable(requiredNamespace))
            }
        }
    }

    suspend fun execute(
        command: String,
        namespace: NetworkNamespace? = null,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): Result = withContext(Dispatchers.IO) {
        val expectedGeneration = closeGeneration.get()
        runInterruptible {
            withInterruptibleLock {
                if (closeGeneration.get() != expectedGeneration) {
                    return@withInterruptibleLock Result(-1, "", "Root shell was closed")
                }
                if (!isShellReady() && !openInternal(expectedGeneration)) {
                    return@withInterruptibleLock Result(-1, "", "Root shell not available")
                }
                if (closeGeneration.get() != expectedGeneration) {
                    closeInternal()
                    return@withInterruptibleLock Result(-1, "", "Root shell was closed")
                }
                val resolvedNamespace = namespace ?: defaultNamespace
                if (resolvedNamespace == NetworkNamespace.CURRENT && !appNamespaceAvailable) {
                    return@withInterruptibleLock Result(-1, "", "App network namespace is not accessible")
                }
                if (resolvedNamespace == NetworkNamespace.INIT && !initNamespaceAvailable) {
                    return@withInterruptibleLock Result(-1, "", "Init network namespace is not accessible")
                }
                try {
                    executeInternal(command, resolvedNamespace, timeoutMs)
                } catch (error: InterruptedException) {
                    closeInternal()
                    throw error
                }
            }
        }
    }

    fun defaultNetworkNamespace(): NetworkNamespace = defaultNamespace

    private fun isNamespaceAvailable(namespace: NetworkNamespace): Boolean = when (namespace) {
        NetworkNamespace.CURRENT -> appNamespaceAvailable
        NetworkNamespace.INIT -> initNamespaceAvailable
    }

    fun close() {
        closeGeneration.incrementAndGet()
        runCatching { process?.destroyForcibly() }
        if (!lock.tryLock()) return
        try {
            closeInternal()
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> withInterruptibleLock(block: () -> T): T {
        lock.lockInterruptibly()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun openInternal(expectedGeneration: Long): Boolean {
        if (isShellReady()) return true
        closeInternal()
        return runCatching {
            val rootProcess = ProcessBuilder("su").redirectErrorStream(false).start()
            process = rootProcess
            if (closeGeneration.get() != expectedGeneration) {
                rootProcess.destroyForcibly()
                process = null
                return false
            }
            stdin = OutputStreamWriter(rootProcess.outputStream)
            stdout = rootProcess.inputStream.bufferedReader()
            stderr = rootProcess.errorStream.bufferedReader()
            stdoutEvents = LinkedBlockingQueue()
            stderrEvents = LinkedBlockingQueue()
            stdoutPump = startStreamPump("root-stdout", stdout, stdoutEvents)
            stderrPump = startStreamPump("root-stderr", stderr, stderrEvents)

            directNamespaces = setOf(NetworkNamespace.CURRENT)
            appNamespaceAvailable = true
            if (executeInternal("id -u", NetworkNamespace.CURRENT).output.trim() != "0") {
                closeInternal()
                return false
            }

            val namespaces = executeInternal(
                "printf 'shell=%s\\napp=%s\\ninit=%s\\n' " +
                    "\"\$(readlink /proc/\$\$/ns/net 2>/dev/null)\" " +
                    "\"\$(readlink /proc/$appProcessId/ns/net 2>/dev/null)\" " +
                    "\"\$(readlink /proc/1/ns/net 2>/dev/null)\"",
                NetworkNamespace.CURRENT,
            ).output.let(::parseTaggedRootValues)
            directNamespaces = detectDirectRootShellNamespaces(
                shellNamespaceId = namespaces["shell"],
                appNamespaceId = namespaces["app"],
                initNamespaceId = namespaces["init"],
            )
            appNamespaceAvailable = NetworkNamespace.CURRENT in directNamespaces ||
                executeInternal("true", NetworkNamespace.CURRENT).isSuccess
            initNamespaceAvailable = NetworkNamespace.INIT in directNamespaces ||
                executeInternal("true", NetworkNamespace.INIT).isSuccess
            if (!initNamespaceAvailable && !appNamespaceAvailable) error("No usable root network namespace")
            defaultNamespace = NetworkNamespace.INIT
            true
        }.getOrElse { error ->
            closeInternal()
            if (error is InterruptedException) throw error
            false
        }
    }

    private fun executeInternal(
        command: String,
        namespace: NetworkNamespace,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): Result {
        val writer = stdin ?: return Result(-1, "", "Shell closed")
        val marker = "XRAY_CMD_DONE_${System.nanoTime()}"
        val exitMarker = "XRAY_EXIT_${System.nanoTime()}"
        val errorMarker = "XRAY_STDERR_DONE_${System.nanoTime()}"

        writer.write("${wrapRootCommand(command, namespace, directNamespaces, appProcessId)}\n")
        writer.write("__xray_status=${'$'}?; printf '\n%s%s\n' '$exitMarker' \"${'$'}__xray_status\"\n")
        writer.write("printf '%s\n' '$marker'\n")
        writer.write("printf '%s\n' '$errorMarker' >&2\n")
        writer.flush()

        val outputLines = mutableListOf<String>()
        var exitCode = -1
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        var commandFinished = false
        while (!commandFinished) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                closeInternal()
                return Result(-1, outputLines.joinToString("\n"), "Root command timed out after ${timeoutMs}ms: $command")
            }
            when (val event = stdoutEvents.poll(remainingNanos, TimeUnit.NANOSECONDS)) {
                null -> {
                    closeInternal()
                    return Result(-1, outputLines.joinToString("\n"), "Root command timed out after ${timeoutMs}ms: $command")
                }
                StreamEvent.Closed -> {
                    closeInternal()
                    return Result(-1, outputLines.joinToString("\n"), "Root shell closed while running: $command")
                }
                is StreamEvent.Line -> when {
                    event.value == marker -> commandFinished = true
                    event.value.startsWith(exitMarker) -> exitCode = event.value.removePrefix(exitMarker).toIntOrNull() ?: -1
                    else -> outputLines += event.value
                }
            }
        }

        val errorLines = mutableListOf<String>()
        var errorFinished = false
        while (!errorFinished) {
            val queuedEvent = stderrEvents.poll()
            if (queuedEvent != null) {
                when (val event: StreamEvent = queuedEvent) {
                    StreamEvent.Closed -> {
                        closeInternal()
                        return Result(-1, outputLines.joinToString("\n"), "Root shell stderr closed while running: $command")
                    }
                    is StreamEvent.Line -> if (event.value == errorMarker) {
                        errorFinished = true
                    } else {
                        errorLines += event.value
                    }
                }
                continue
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                closeInternal()
                return Result(-1, outputLines.joinToString("\n"), "Root command stderr timed out after ${timeoutMs}ms: $command")
            }
            when (val event = stderrEvents.poll(remainingNanos, TimeUnit.NANOSECONDS)) {
                null -> {
                    closeInternal()
                    return Result(-1, outputLines.joinToString("\n"), "Root command stderr timed out after ${timeoutMs}ms: $command")
                }
                StreamEvent.Closed -> {
                    closeInternal()
                    return Result(-1, outputLines.joinToString("\n"), "Root shell stderr closed while running: $command")
                }
                is StreamEvent.Line -> if (event.value == errorMarker) {
                    errorFinished = true
                } else {
                    errorLines += event.value
                }
            }
        }
        val errorOutput = errorLines.joinToString("\n")
        return Result(exitCode, outputLines.joinToString("\n"), errorOutput)
    }

    private fun isShellReady(): Boolean = process?.isAlive == true && stdoutPump?.isAlive == true

    private fun startStreamPump(
        name: String,
        reader: BufferedReader?,
        events: LinkedBlockingQueue<StreamEvent>,
    ): Thread? = reader?.let { stream ->
        Thread({
            try {
                while (true) {
                    val line = stream.readLine() ?: break
                    events.put(StreamEvent.Line(line))
                }
            } catch (_: Exception) {
                // Closing the shell interrupts the blocking stream read.
            } finally {
                events.offer(StreamEvent.Closed)
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun closeInternal() {
        runCatching {
            stdin?.write("exit\n")
            stdin?.flush()
        }
        runCatching { process?.destroyForcibly() }
        stdoutPump?.interrupt()
        stderrPump?.interrupt()
        process = null
        stdin = null
        stdout = null
        stderr = null
        stdoutPump = null
        stderrPump = null
        stdoutEvents = LinkedBlockingQueue()
        stderrEvents = LinkedBlockingQueue()
        directNamespaces = emptySet()
        appNamespaceAvailable = false
        initNamespaceAvailable = false
        defaultNamespace = NetworkNamespace.INIT
    }

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }

    private sealed interface StreamEvent {
        data class Line(val value: String) : StreamEvent
        data object Closed : StreamEvent
    }
}

internal fun detectDirectRootShellNamespaces(
    shellNamespaceId: String?,
    appNamespaceId: String?,
    initNamespaceId: String?,
): Set<RootShell.NetworkNamespace> = buildSet {
    if (shellNamespaceId.isNullOrBlank()) return@buildSet
    if (shellNamespaceId == appNamespaceId) add(RootShell.NetworkNamespace.CURRENT)
    if (shellNamespaceId == initNamespaceId) add(RootShell.NetworkNamespace.INIT)
}

internal fun parseTaggedRootValues(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
    val separator = line.indexOf('=')
    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
}.toMap()

internal fun wrapRootCommand(
    command: String,
    requestedNamespace: RootShell.NetworkNamespace,
    directNamespaces: Set<RootShell.NetworkNamespace>,
    appProcessId: Int,
): String {
    if (requestedNamespace in directNamespaces) return "sh -c ${shellQuote(command)}"
    val targetPid = if (requestedNamespace == RootShell.NetworkNamespace.INIT) 1 else appProcessId
    return "nsenter -t $targetPid -n -- sh -c ${shellQuote(command)}"
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
