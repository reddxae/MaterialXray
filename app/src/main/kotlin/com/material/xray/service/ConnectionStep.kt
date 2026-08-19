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
    val reported: Boolean = true,
    val slowSuccessLogThresholdMs: Long? = null,
    val action: suspend () -> T,
) {
    init {
        require(maxRetries >= 0)
        require(retryDelayMs >= 0)
        require(retryable == (maxRetries > 0))
        require(slowSuccessLogThresholdMs == null || slowSuccessLogThresholdMs >= 0)
    }
}

internal class ConnectionStepExecutor(
    private val elapsedRealtime: () -> Long,
    private val log: (String) -> Unit,
    private val onProgressStarted: (ConnectionProgress) -> Long,
    private val onProgressFinished: (Long) -> Unit,
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> execute(step: ConnectionStep<T>): T {
        val progressToken = step.progress?.takeIf { step.reported }?.let(onProgressStarted)
        var retries = 0
        val maxAttempts = step.maxRetries + 1
        try {
            while (true) {
                val attempt = retries + 1
                val outcome = executeAttempt(step, attempt, maxAttempts)
                if (outcome is ConnectionStepOutcome.Success) return outcome.value
                step.revertAction?.let { execute(it) }
                if (!step.retryable || retries == step.maxRetries) {
                    return when (outcome) {
                        is ConnectionStepOutcome.Success -> outcome.value
                        is ConnectionStepOutcome.Unsuccessful -> outcome.value
                        is ConnectionStepOutcome.Error -> throw outcome.error
                    }
                }
                retries++
                if (step.reported) log("Retrying ${step.label} ($retries/${step.maxRetries})...")
                waitBeforeRetry(step.retryDelayMs)
            }
        } finally {
            progressToken?.let(onProgressFinished)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> executeAttempt(
        step: ConnectionStep<T>,
        attempt: Int,
        maxAttempts: Int,
    ): ConnectionStepOutcome<T> {
        val startedAt = elapsedRealtime()
        if (step.reported && step.slowSuccessLogThresholdMs == null) {
            val attemptSuffix = if (maxAttempts > 1) " (attempt $attempt/$maxAttempts)" else ""
            log("${step.label}$attemptSuffix...")
        }
        return try {
            val value = step.action()
            val elapsedMs = elapsedRealtime() - startedAt
            if (step.isSuccessful(value)) {
                if (
                    step.reported &&
                    (step.slowSuccessLogThresholdMs == null || elapsedMs > step.slowSuccessLogThresholdMs)
                ) {
                    log("${step.label} took $elapsedMs ms")
                }
                ConnectionStepOutcome.Success(value)
            } else {
                if (step.reported) log("${step.label} failed after $elapsedMs ms")
                ConnectionStepOutcome.Unsuccessful(value)
            }
        } catch (error: CancellationException) {
            if (step.reported) log("${step.label} cancelled after ${elapsedRealtime() - startedAt} ms")
            revertAfterCancellation(step, error)
            throw error
        } catch (error: Exception) {
            val elapsedMs = elapsedRealtime() - startedAt
            if (step.reported) {
                log(
                    "${step.label} failed after $elapsedMs ms: " +
                        (error.message ?: error::class.java.simpleName),
                )
            }
            ConnectionStepOutcome.Error(error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> revertAfterCancellation(step: ConnectionStep<T>, cancellation: CancellationException) {
        val revertAction = step.revertAction ?: return
        try {
            withContext(NonCancellable) { execute(revertAction) }
        } catch (revertError: Exception) {
            cancellation.addSuppressed(revertError)
        }
    }
}

private sealed interface ConnectionStepOutcome<out T> {
    data class Success<T>(val value: T) : ConnectionStepOutcome<T>
    data class Unsuccessful<T>(val value: T) : ConnectionStepOutcome<T>
    data class Error(val error: Exception) : ConnectionStepOutcome<Nothing>
}
