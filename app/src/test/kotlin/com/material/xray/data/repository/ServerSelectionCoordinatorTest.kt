package com.material.xray.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSelectionCoordinatorTest {
    @Test
    fun `selection operations are serialized`() = runTest {
        val coordinator = ServerSelectionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = async {
            coordinator.withSelectionLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            coordinator.withSelectionLock {
                secondEntered = true
            }
        }

        yield()
        assertFalse(secondEntered)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
    }
}
