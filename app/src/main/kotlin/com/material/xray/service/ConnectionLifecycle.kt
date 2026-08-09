package com.material.xray.service

import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ConnectionRequest(
    val config: ServerConfig,
    val transitionState: ConnectionState = ConnectionState.Connecting,
    val preparation: ConnectionPreparation = ConnectionPreparation.Full,
)

internal enum class ConnectionPreparation {
    Full,
    ReusePreparedRuntime,
    FastServerSwitch,
    ;

    val cleansPreviousState: Boolean
        get() = this == Full

    val reusesStaticRuntime: Boolean
        get() = this == FastServerSwitch
}

internal data class ConnectionFailure(
    val message: String,
    val retryable: Boolean,
)

internal class ConnectionLifecycle(
    private val scope: CoroutineScope,
    private val maxAttempts: Int,
    private val retryDelayMs: Long,
    private val beforeCommand: () -> Unit,
    private val afterCommand: () -> Unit,
    private val runAttempt: suspend (ConnectionRequest) -> Boolean,
    private val currentFailure: () -> ConnectionFailure,
    private val onRetry: (attempt: Int, maxAttempts: Int, transitionState: ConnectionState) -> Unit,
    private val onConnected: () -> Unit,
    private val onExhausted: (ConnectionFailure) -> Unit,
    private val onCommandFailure: suspend (Throwable) -> Unit,
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    private val commandMutex = Mutex()

    @Volatile
    var activeConfig: ServerConfig? = null
        private set

    fun updateActiveConfig(config: ServerConfig?) {
        activeConfig = config
    }

    /**
     * Runs [block] as a serialized command.
     *
     * A command owns the tunnel while it runs, so an unexpected throwable must not escape into the
     * dispatcher's uncaught handler: that would kill the process with the tunnel still established
     * and skip every teardown. [onCommandFailure] therefore runs while the command lock and the
     * command wake lock are still held, which lets it tear the runtime down safely.
     */
    // Catching Throwable is the point here: this is the last barrier before the dispatcher's
    // uncaught handler, which would kill the process with the tunnel still established.
    @Suppress("TooGenericExceptionCaught")
    fun launch(block: suspend () -> Unit) {
        scope.launch {
            serialized {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onCommandFailure(error)
                }
            }
        }
    }

    suspend fun <T> serialized(block: suspend () -> T): T = commandMutex.withLock {
        beforeCommand()
        try {
            return@withLock block()
        } finally {
            afterCommand()
        }
    }

    suspend fun connect(request: ConnectionRequest): Boolean {
        var attempt = 1
        var failure = currentFailure()
        while (attempt <= maxAttempts && currentCoroutineContext().isActive) {
            if (attempt > 1) onRetry(attempt, maxAttempts, request.transitionState)

            val connected = runAttempt(
                request.copy(
                    preparation = if (attempt > 1) ConnectionPreparation.Full else request.preparation,
                ),
            )
            if (connected) {
                onConnected()
                return true
            }

            failure = currentFailure()
            if (!failure.retryable || attempt == maxAttempts) break
            waitBeforeRetry(retryDelayMs)
            attempt++
        }

        onExhausted(failure)
        return false
    }
}
