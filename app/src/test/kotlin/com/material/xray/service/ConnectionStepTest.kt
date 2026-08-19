package com.material.xray.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStepTest {
    @Test
    fun `retryable step reverts only failed attempts`() = runTest {
        val order = mutableListOf<String>()
        val results = mutableListOf(false, false, true)
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = {},
            waitBeforeRetry = { order += "wait" },
        )
        val revert = ConnectionStep(
            label = "revert",
            action = { order += "revert" },
        )
        val step = ConnectionStep(
            label = "operation",
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
    }

    @Test
    fun `cancelled step reverts before propagating cancellation`() = runTest {
        var reverted = false
        val executor = ConnectionStepExecutor(elapsedRealtime = { 0 }, log = {})
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
        assertEquals("cancelled", cancellation?.message)
    }
}
