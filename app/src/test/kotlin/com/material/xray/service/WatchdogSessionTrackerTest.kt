package com.material.xray.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogSessionTrackerTest {
    @Test
    fun `stopped session cannot authorize delayed recovery`() {
        val tracker = WatchdogSessionTracker()
        val stopped = tracker.start(pid = 41)

        tracker.stop()

        assertFalse(tracker.matches(stopped, currentPid = 41))
    }

    @Test
    fun `replacement session invalidates blocked check for reused pid`() {
        val tracker = WatchdogSessionTracker()
        val stale = tracker.start(pid = 42)
        tracker.stop()
        val replacement = tracker.start(pid = 42)

        assertFalse(tracker.matches(stale, currentPid = 42))
        assertTrue(tracker.matches(replacement, currentPid = 42))
    }

    @Test
    fun `session must still own connected process`() {
        val tracker = WatchdogSessionTracker()
        val session = tracker.start(pid = 42)

        assertFalse(tracker.matches(session, currentPid = 43))
        assertTrue(tracker.matches(session, currentPid = 42))
    }
}
