package com.material.xray.data.repository

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionRefreshStateTest {
    @Test
    fun `refresh tracks only its subscription and clears after success`() = runTest {
        val state = SubscriptionRefreshState()
        assertEquals(emptySet<Long>(), state.refreshingIds.value)

        val result = state.withRefreshLock(1) {
            assertEquals(setOf(1L), state.refreshingIds.value)
            "updated"
        }

        assertEquals("updated", result)
        assertEquals(emptySet<Long>(), state.refreshingIds.value)
    }

    @Test
    fun `failed refresh clears progress and propagates the error`() = runTest {
        val state = SubscriptionRefreshState()
        val failure = IOException("Refresh failed")

        try {
            state.withRefreshLock(1) {
                assertEquals(setOf(1L), state.refreshingIds.value)
                throw failure
            }
            fail("Expected refresh failure")
        } catch (error: IOException) {
            assertSame(failure, error)
        }

        assertEquals(emptySet<Long>(), state.refreshingIds.value)
    }

    @Test
    fun `finishing or cancelling one refresh leaves other subscriptions updating`() = runTest {
        val state = SubscriptionRefreshState()
        val finishFirst = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshLock(1) { finishFirst.await() }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshLock(2) { awaitCancellation() }
        }
        assertEquals(setOf(1L, 2L), state.refreshingIds.value)

        finishFirst.complete(Unit)
        first.join()
        assertEquals(setOf(2L), state.refreshingIds.value)

        second.cancelAndJoin()
        assertEquals(emptySet<Long>(), state.refreshingIds.value)
    }

    @Test
    fun `queued refresh of the same subscription cannot clear active progress`() = runTest {
        val state = SubscriptionRefreshState()
        val finishFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshLock(1) { finishFirst.await() }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshLock(1) { fail("Cancelled refresh should not run") }
        }
        cancelled.cancelAndJoin()
        assertEquals(setOf(1L), state.refreshingIds.value)

        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshLock(1) {
                secondStarted.complete(Unit)
                awaitCancellation()
            }
        }
        assertEquals(false, secondStarted.isCompleted)

        finishFirst.complete(Unit)
        first.join()
        secondStarted.await()
        assertEquals(setOf(1L), state.refreshingIds.value)

        second.cancelAndJoin()
        assertEquals(emptySet<Long>(), state.refreshingIds.value)
    }

    @Test
    fun `refresh is busy while waiting for coordinator serialization`() = runTest {
        val state = SubscriptionRefreshState()
        val operationMutex = Mutex(locked = true)
        val started = CompletableDeferred<Unit>()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshTracking(2) {
                operationMutex.withLock {
                    state.withRefreshLock(2) {
                        assertEquals(setOf(2L), state.refreshingIds.value)
                    }
                    assertEquals(setOf(2L), state.refreshingIds.value)
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        assertEquals(setOf(2L), state.refreshingIds.value)
        assertEquals(false, started.isCompleted)

        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            state.withRefreshTracking(3) {
                operationMutex.withLock { fail("Cancelled refresh should not run") }
            }
        }
        assertEquals(setOf(2L, 3L), state.refreshingIds.value)
        cancelled.cancelAndJoin()
        assertEquals(setOf(2L), state.refreshingIds.value)

        operationMutex.unlock()
        started.await()
        refresh.cancelAndJoin()
        assertEquals(emptySet<Long>(), state.refreshingIds.value)
    }
}
