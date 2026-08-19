package com.material.xray.service

import com.material.xray.model.ConnectionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStepTest {
    @Test
    fun `retryable step reverts only failed attempts`() = runTest {
        val order = mutableListOf<String>()
        val progress = mutableListOf<ConnectionProgress>()
        val results = mutableListOf(false, false, true)
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = {},
            onProgress = progress::add,
            waitBeforeRetry = { order += "wait" },
        )
        val revert = ConnectionStep(
            label = "revert",
            action = { order += "revert" },
        )
        val step = ConnectionStep(
            label = "operation",
            progress = ConnectionProgress.ResolvingEntryServer,
            retryable = true,
            maxRetries = 2,
            retryDelayMs = 1,
            revertAction = revert,
            isSuccessful = { it },
            action = {
                order += "attempt"
                results.removeAt(0)
            },
        )

        assertTrue(executor.execute(step))
        assertEquals(
            listOf("attempt", "revert", "wait", "attempt", "revert", "wait", "attempt"),
            order,
        )
        assertEquals(List(3) { ConnectionProgress.ResolvingEntryServer }, progress)
    }

    @Test
    fun `cancelled step reverts before propagating cancellation`() = runTest {
        var reverted = false
        val executor = ConnectionStepExecutor(elapsedRealtime = { 0 }, log = {}, onProgress = {})
        val step = ConnectionStep(
            label = "operation",
            revertAction = ConnectionStep(
                label = "revert",
                action = { reverted = true },
            ),
            action = { throw CancellationException("cancelled") },
        )

        var cancellation: CancellationException? = null
        try {
            executor.execute(step)
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertTrue(reverted)
        assertEquals("cancelled", cancellation.message)
    }
}
