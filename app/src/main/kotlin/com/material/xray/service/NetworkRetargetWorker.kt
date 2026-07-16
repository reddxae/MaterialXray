package com.material.xray.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class NetworkRetargetSignal(
    val reason: String,
    val settle: Boolean,
    val passive: Boolean,
)

internal enum class NetworkRetargetResult {
    Done,
    Retry,
}

internal enum class NetworkRetargetRetryOutcome {
    Stabilized,
    Exhausted,
    Stopped,
}

@Suppress("TooGenericExceptionCaught")
internal class NetworkRetargetWorker(
    scope: CoroutineScope,
    private val settleDelayMs: Long,
    private val shouldHandle: () -> Boolean,
    private val beforeBatch: () -> Unit,
    private val afterBatch: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val handle: suspend (String) -> Unit,
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val pendingLock = Any()
    private var pendingSignal: NetworkRetargetSignal? = null
    private val job: Job = scope.launch {
        for (ignored in wakeups) {
            val initialSignal = takePendingSignal() ?: continue
            if (shouldHandle()) handleBatch(initialSignal)
        }
    }

    fun signal(reason: String, settle: Boolean, passive: Boolean = false) {
        val candidate = NetworkRetargetSignal(reason, settle, passive)
        synchronized(pendingLock) {
            pendingSignal = pendingSignal?.let { current -> preferredSignal(current, candidate) } ?: candidate
        }
        wakeups.trySend(Unit)
    }

    fun close() {
        wakeups.close()
        job.cancel()
    }

    private fun takePendingSignal(): NetworkRetargetSignal? = synchronized(pendingLock) {
        pendingSignal.also { pendingSignal = null }
    }

    private suspend fun handleBatch(initialSignal: NetworkRetargetSignal) {
        beforeBatch()
        try {
            var latestSignal = initialSignal
            takePendingSignal()?.let { pending ->
                latestSignal = preferredSignal(latestSignal, pending)
            }
            if (latestSignal.settle) {
                delay(settleDelayMs)
                takePendingSignal()?.let { pending ->
                    latestSignal = preferredSignal(latestSignal, pending)
                }
            }
            if (shouldHandle()) handle(latestSignal.reason)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            afterBatch()
        }
    }

    private fun preferredSignal(
        current: NetworkRetargetSignal,
        candidate: NetworkRetargetSignal,
    ): NetworkRetargetSignal = when {
        current.passive && !candidate.passive -> candidate
        !current.passive && candidate.passive -> current
        else -> candidate
    }
}

internal suspend fun retryNetworkRetarget(
    retryDelaysMs: List<Long>,
    shouldContinue: () -> Boolean = { true },
    retarget: suspend (attempt: Int) -> NetworkRetargetResult,
): NetworkRetargetRetryOutcome {
    var attempt = 1
    while (currentCoroutineContext().isActive) {
        if (!shouldContinue()) return NetworkRetargetRetryOutcome.Stopped
        if (retarget(attempt) == NetworkRetargetResult.Done) return NetworkRetargetRetryOutcome.Stabilized
        if (!shouldContinue()) return NetworkRetargetRetryOutcome.Stopped

        val retryDelayMs = retryDelaysMs.getOrNull(attempt - 1)
            ?: return NetworkRetargetRetryOutcome.Exhausted
        delay(retryDelayMs)
        attempt++
    }
    return NetworkRetargetRetryOutcome.Stopped
}
