package com.material.xray.service

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutingChangeManagerTest {
    @Test
    fun `xray and app routing changes require a full config reload`() {
        assertEquals(
            PendingRoutingChange.XRAY_CONFIG,
            combinePendingRoutingChanges(
                PendingRoutingChange.APP_ROUTING,
                PendingRoutingChange.XRAY_ROUTING,
            ),
        )
    }

    @Test
    fun `same narrow change remains narrow`() {
        assertEquals(
            PendingRoutingChange.XRAY_ROUTING,
            combinePendingRoutingChanges(
                PendingRoutingChange.XRAY_ROUTING,
                PendingRoutingChange.XRAY_ROUTING,
            ),
        )
    }

    @Test
    fun `concurrent narrow changes are combined atomically`() {
        val store = PendingRoutingChangeStore()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val threads = listOf(
            thread(start = true) {
                ready.countDown()
                start.await()
                store.mark(PendingRoutingChange.APP_ROUTING)
            },
            thread(start = true) {
                ready.countDown()
                start.await()
                store.mark(PendingRoutingChange.XRAY_ROUTING)
            },
        )

        ready.await()
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(PendingRoutingChange.XRAY_CONFIG, store.take())
        assertFalse(store.hasPendingChanges.value)
    }
}
