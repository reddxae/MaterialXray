package com.material.xray.service

import com.material.xray.model.ConnectionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class ConnectionStep<T>(
    val label: String,
    val progress: ConnectionProgress? = null,
    val retryable: Boolean = false,
    val maxRetries: Int = 0,
    val retryDelayMs: Long = 0,
    val revertAction: ConnectionStep<Unit>? = null,
    val isSuccessful: (T) -> Boolean = { true },
    val action: suspend () -> T,
) {
    init {
        require(maxRetries >= 0)
        require(retryDelayMs >= 0)
        require(retryable == (maxRetries > 0))
    }
}

internal class ConnectionStepExecutor(
    private val elapsedRealtime: () -> Long,
    private val log: (String) -> Unit,
    private val onProgress: (ConnectionProgress) -> Unit,
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> execute(step: ConnectionStep<T>): T {
        var retries = 0
        while (true) {
            step.progress?.let(onProgress)
            val outcome: ConnectionStepOutcome<T> = try {
                ConnectionStepOutcome.Value(timed(step))
            } catch (error: CancellationException) {
                step.revertAction?.let { revertAction ->
                    try {
                        withContext(NonCancellable) { execute(revertAction) }
                    } catch (revertError: Exception) {
                        error.addSuppressed(revertError)
                    }
                }
                throw error
            } catch (error: Exception) {
                ConnectionStepOutcome.Failure(error)
            }
            if (outcome is ConnectionStepOutcome.Value && step.isSuccessful(outcome.value)) {
                return outcome.value
            }
            step.revertAction?.let { execute(it) }
            if (!step.retryable || retries == step.maxRetries) {
                return when (outcome) {
                    is ConnectionStepOutcome.Value -> outcome.value
                    is ConnectionStepOutcome.Failure -> throw outcome.error
                }
            }

            retries++
            log("Retrying ${step.label} ($retries/${step.maxRetries})...")
            waitBeforeRetry(step.retryDelayMs)
        }
    }

    private suspend fun <T> timed(step: ConnectionStep<T>): T {
        val startedAt = elapsedRealtime()
        return try {
            step.action()
        } finally {
            log("${step.label} took ${elapsedRealtime() - startedAt} ms")
        }
    }
}

private sealed interface ConnectionStepOutcome<out T> {
    data class Value<T>(val value: T) : ConnectionStepOutcome<T>
    data class Failure(val error: Exception) : ConnectionStepOutcome<Nothing>
}
