package com.material.xray.service

import com.material.xray.model.ConnectionProgress
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStepTest {
    @Test
    fun `retryable step reverts only failed attempts`() = runTest {
        val order = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val results = mutableListOf(false, false, true)
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = logs::add,
            onProgressStarted = {
                progress += "start:$it"
                1L
            },
            onProgressFinished = { progress += "finish:$it" },
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
        assertEquals(
            listOf(
                "operation (attempt 1/3)...",
                "operation failed after 0 ms",
                "revert...",
                "revert took 0 ms",
                "Retrying operation (1/2)...",
                "operation (attempt 2/3)...",
                "operation failed after 0 ms",
                "revert...",
                "revert took 0 ms",
                "Retrying operation (2/2)...",
                "operation (attempt 3/3)...",
                "operation took 0 ms",
            ),
            logs,
        )
        assertEquals(listOf("start:ResolvingEntryServer", "finish:1"), progress)
    }

    @Test
    fun `cancelled step reverts before propagating cancellation`() = runTest {
        var reverted = false
        val logs = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = logs::add,
            onProgressStarted = {
                progress += "start:$it"
                1L
            },
            onProgressFinished = { progress += "finish:$it" },
        )
        val step = ConnectionStep(
            label = "operation",
            progress = ConnectionProgress.StoppingCore,
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
        assertEquals(
            listOf(
                "operation...",
                "operation cancelled after 0 ms",
                "revert...",
                "revert took 0 ms",
            ),
            logs,
        )
        assertEquals(listOf("start:StoppingCore", "finish:1"), progress)
    }

    @Test
    fun `step exception is logged with its outcome and duration`() = runTest {
        val logs = mutableListOf<String>()
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = logs::add,
            onProgressStarted = { 1L },
            onProgressFinished = {},
        )
        val step = ConnectionStep(
            label = "operation",
            action = { throw IllegalStateException("boom") },
        )

        var failure: IllegalStateException? = null
        try {
            executor.execute(step)
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertEquals("boom", failure?.message)
        assertEquals(
            listOf(
                "operation...",
                "operation failed after 0 ms: boom",
            ),
            logs,
        )
    }

    @Test
    fun `retryable step retries an exception without losing outcome logs`() = runTest {
        val logs = mutableListOf<String>()
        var attempts = 0
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = logs::add,
            onProgressStarted = { 1L },
            onProgressFinished = {},
            waitBeforeRetry = {},
        )
        val step = ConnectionStep(
            label = "operation",
            retryable = true,
            maxRetries = 1,
            action = {
                attempts++
                if (attempts == 1) throw IOException("temporary")
                true
            },
        )

        assertTrue(executor.execute(step))
        assertEquals(
            listOf(
                "operation (attempt 1/2)...",
                "operation failed after 0 ms: temporary",
                "Retrying operation (1/1)...",
                "operation (attempt 2/2)...",
                "operation took 0 ms",
            ),
            logs,
        )
    }

    @Test
    fun `unreported step emits no logs or progress`() = runTest {
        val logs = mutableListOf<String>()
        val progress = mutableListOf<ConnectionProgress>()
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { 0 },
            log = logs::add,
            onProgressStarted = {
                progress += it
                1L
            },
            onProgressFinished = {},
        )

        executor.execute(
            ConnectionStep(
                label = "Physical route probe",
                progress = ConnectionProgress.UpdatingNetworkRoute,
                reported = false,
                action = { true },
            ),
        )

        assertTrue(logs.isEmpty())
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `slow success threshold logs only success above threshold`() = runTest {
        var now = 0L
        val logs = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val executor = ConnectionStepExecutor(
            elapsedRealtime = { now },
            log = logs::add,
            onProgressStarted = {
                progress += "start:$it"
                1L
            },
            onProgressFinished = { progress += "finish:$it" },
        )

        executor.execute(
            ConnectionStep(
                label = "Stabilize physical network route",
                progress = ConnectionProgress.UpdatingNetworkRoute,
                slowSuccessLogThresholdMs = 500,
                action = { now = 500 },
            ),
        )
        executor.execute(
            ConnectionStep(
                label = "Stabilize physical network route",
                progress = ConnectionProgress.UpdatingNetworkRoute,
                slowSuccessLogThresholdMs = 500,
                action = { now = 1_001 },
            ),
        )

        assertEquals(listOf("Stabilize physical network route took 501 ms"), logs)
        assertEquals(
            listOf(
                "start:UpdatingNetworkRoute",
                "finish:1",
                "start:UpdatingNetworkRoute",
                "finish:1",
            ),
            progress,
        )
    }
}
