package com.material.xray.service

import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRetargetWorkerTest {

    @Test
    fun `callback burst is conflated before retargeting`() = runTest {
        val handledReasons = mutableListOf<String>()
        var acquired = 0
        var released = 0
        val worker = NetworkRetargetWorker(
            scope = this,
            settleDelayMs = SETTLE_DELAY_MS,
            shouldHandle = { true },
            beforeBatch = { acquired++ },
            afterBatch = { released++ },
            handle = handledReasons::add,
        )

        worker.signal("capabilities changed", settle = true)
        worker.signal("link properties changed", settle = true)
        runCurrent()

        assertTrue(handledReasons.isEmpty())
        assertEquals(1, acquired)
        advanceTimeBy(SETTLE_DELAY_MS)
        runCurrent()

        assertEquals(listOf("link properties changed"), handledReasons)
        assertEquals(1, released)
        worker.close()
    }

    @Test
    fun `event during retarget schedules one follow-up pass`() = runTest {
        val handledReasons = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val worker = NetworkRetargetWorker(
            scope = this,
            settleDelayMs = SETTLE_DELAY_MS,
            shouldHandle = { true },
            beforeBatch = {},
            afterBatch = {},
            handle = { reason ->
                handledReasons += reason
                if (reason == "available") {
                    firstStarted.complete(Unit)
                    finishFirst.await()
                }
            },
        )

        worker.signal("available", settle = false)
        runCurrent()
        firstStarted.await()
        worker.signal("capabilities changed", settle = true)
        worker.signal("link properties changed", settle = true)
        finishFirst.complete(Unit)
        runCurrent()
        advanceTimeBy(SETTLE_DELAY_MS)
        runCurrent()

        assertEquals(listOf("available", "link properties changed"), handledReasons)
        worker.close()
    }

    @Test
    fun `retarget retries with configured backoff until stable`() = runTest {
        val attempts = mutableListOf<Int>()

        val stabilized = retryNetworkRetarget(listOf(250L, 500L, 1_000L)) { attempt ->
            attempts += attempt
            if (attempt == 3) NetworkRetargetResult.Done else NetworkRetargetResult.Retry
        }

        assertTrue(stabilized)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(750L, testScheduler.currentTime)
    }

    @Test
    fun `retarget reports exhaustion after final retry`() = runTest {
        val attempts = mutableListOf<Int>()

        val stabilized = retryNetworkRetarget(listOf(250L, 500L)) { attempt ->
            attempts += attempt
            NetworkRetargetResult.Retry
        }

        assertFalse(stabilized)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(750L, testScheduler.currentTime)
    }

    @Test
    fun `restoration preserves persisted route for reconciliation`() {
        val fallback = TunManager.PhysicalRoute(dev = "rmnet0", gateway = "10.0.0.1", table = "main")
        val state = XrayState(
            physicalInterface = "wlan0",
            physicalGateway = "192.168.1.1",
            physicalTable = "main",
        )

        val restored = selectRestoredPhysicalRoute(state, fallback)

        assertEquals(TunManager.PhysicalRoute("wlan0", "192.168.1.1", "main"), restored)
    }

    @Test
    fun `restoration uses current route when persisted interface is missing`() {
        val fallback = TunManager.PhysicalRoute(dev = "rmnet0", gateway = "10.0.0.1", table = "main")

        val restored = selectRestoredPhysicalRoute(XrayState(physicalInterface = null), fallback)

        assertEquals(fallback, restored)
    }

    private companion object {
        const val SETTLE_DELAY_MS = 250L
    }
}
