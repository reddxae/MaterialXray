package com.material.xray.service

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLifecycleTest {
    @Test
    fun `connection failure does not rerun the entire attempt`() = runTest {
        val requests = mutableListOf<ConnectionRequest>()
        val lifecycle = lifecycle(
            runAttempt = { request ->
                requests += request
                false
            },
        )

        val connected = lifecycle.connect(
            ConnectionRequest(server(), preparation = ConnectionPreparation.FastServerSwitch),
        )

        assertFalse(connected)
        assertEquals(listOf(ConnectionPreparation.FastServerSwitch), requests.map { it.preparation })
    }

    @Test
    fun `non-retryable failure is published without another attempt`() = runTest {
        var attempts = 0
        var exhausted: ConnectionFailure? = null
        val failure = ConnectionFailure("permission denied", retryable = false)
        val lifecycle = lifecycle(
            runAttempt = {
                attempts++
                false
            },
            currentFailure = { failure },
            onExhausted = { exhausted = it },
        )

        assertFalse(lifecycle.connect(ConnectionRequest(server())))
        assertEquals(1, attempts)
        assertEquals(failure, exhausted)
    }

    @Test
    fun `serialized commands do not overlap`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val lifecycle = lifecycle(runAttempt = { true })

        val first = async {
            lifecycle.serialized {
                order += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstStarted.await()
        val second = async {
            lifecycle.serialized { order += "second" }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("first-start"), order)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun `an unexpected command failure is reported instead of escaping the scope`() = runTest {
        val commandOrder = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val lifecycle = lifecycle(
            runAttempt = { true },
            onCommandFailure = { error ->
                commandOrder += "failure"
                failures += error
            },
        )

        lifecycle.launch { throw IllegalStateException("boom") }
        lifecycle.launch { commandOrder += "next" }
        runCurrent()

        assertEquals(listOf("failure", "next"), commandOrder)
        assertEquals(listOf("boom"), failures.map { it.message })
        assertTrue(backgroundScope.coroutineContext.job.isActive)
    }

    private fun TestScope.lifecycle(
        runAttempt: suspend (ConnectionRequest) -> Boolean,
        currentFailure: () -> ConnectionFailure = { ConnectionFailure("failed", retryable = true) },
        onExhausted: (ConnectionFailure) -> Unit = {},
        onCommandFailure: suspend (Throwable) -> Unit = {},
    ) = ConnectionLifecycle(
        scope = backgroundScope,
        beforeCommand = {},
        afterCommand = {},
        runAttempt = runAttempt,
        currentFailure = currentFailure,
        onConnected = {},
        onExhausted = onExhausted,
        onCommandFailure = onCommandFailure,
    )

    private companion object {
        fun server() = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Test",
            address = "192.0.2.1",
            port = 443,
            password = "test",
        )
    }
}
