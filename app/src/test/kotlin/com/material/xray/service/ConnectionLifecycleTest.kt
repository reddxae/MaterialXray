package com.material.xray.service

import com.material.xray.model.ConnectionState
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLifecycleTest {
    @Test
    fun `retry uses clean setup and disables fast reconnect after first attempt`() = runTest {
        val requests = mutableListOf<ConnectionRequest>()
        val retries = mutableListOf<Int>()
        val lifecycle = lifecycle(
            attempts = 3,
            runAttempt = { request ->
                requests += request
                requests.size == 3
            },
            onRetry = { attempt, _, _ -> retries += attempt },
        )

        val connected = lifecycle.connect(
            ConnectionRequest(server(), preparation = ConnectionPreparation.FastServerSwitch),
        )

        assertTrue(connected)
        assertEquals(
            listOf(
                ConnectionPreparation.FastServerSwitch,
                ConnectionPreparation.Full,
                ConnectionPreparation.Full,
            ),
            requests.map { it.preparation },
        )
        assertEquals(listOf(2, 3), retries)
    }

    @Test
    fun `non-retryable failure is published without another attempt`() = runTest {
        var attempts = 0
        var exhausted: ConnectionFailure? = null
        val failure = ConnectionFailure("permission denied", retryable = false)
        val lifecycle = lifecycle(
            attempts = 3,
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
        val lifecycle = lifecycle(attempts = 1, runAttempt = { true })

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

    private fun TestScope.lifecycle(
        attempts: Int,
        runAttempt: suspend (ConnectionRequest) -> Boolean,
        currentFailure: () -> ConnectionFailure = { ConnectionFailure("failed", retryable = true) },
        onRetry: (Int, Int, ConnectionState) -> Unit = { _, _, _ -> },
        onExhausted: (ConnectionFailure) -> Unit = {},
    ) = ConnectionLifecycle(
        scope = backgroundScope,
        maxAttempts = attempts,
        retryDelayMs = 0,
        beforeCommand = {},
        afterCommand = {},
        runAttempt = runAttempt,
        currentFailure = currentFailure,
        onRetry = onRetry,
        onConnected = {},
        onExhausted = onExhausted,
        waitBeforeRetry = {},
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
