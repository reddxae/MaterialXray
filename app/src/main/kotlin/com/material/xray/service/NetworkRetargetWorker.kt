package com.material.xray.service

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
)

internal enum class NetworkRetargetResult {
    Done,
    Retry,
}

internal class NetworkRetargetWorker(
    scope: CoroutineScope,
    private val settleDelayMs: Long,
    private val shouldHandle: () -> Boolean,
    private val beforeBatch: () -> Unit,
    private val afterBatch: () -> Unit,
    private val handle: suspend (String) -> Unit,
) {
    private val signals = Channel<NetworkRetargetSignal>(Channel.CONFLATED)
    private val job: Job = scope.launch {
        for (initialSignal in signals) {
            if (!shouldHandle()) continue

            beforeBatch()
            try {
                var latestSignal = initialSignal
                if (latestSignal.settle) delay(settleDelayMs)

                while (true) {
                    latestSignal = signals.tryReceive().getOrNull() ?: break
                }

                if (shouldHandle()) handle(latestSignal.reason)
            } finally {
                afterBatch()
            }
        }
    }

    fun signal(reason: String, settle: Boolean) {
        signals.trySend(NetworkRetargetSignal(reason, settle))
    }

    fun close() {
        signals.close()
        job.cancel()
    }
}

internal suspend fun retryNetworkRetarget(
    retryDelaysMs: List<Long>,
    retarget: suspend (attempt: Int) -> NetworkRetargetResult,
): Boolean {
    var attempt = 1
    while (currentCoroutineContext().isActive) {
        if (retarget(attempt) == NetworkRetargetResult.Done) return true

        val retryDelayMs = retryDelaysMs.getOrNull(attempt - 1) ?: return false
        delay(retryDelayMs)
        attempt++
    }
    return false
}
