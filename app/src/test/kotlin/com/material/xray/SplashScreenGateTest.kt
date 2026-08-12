package com.material.xray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashScreenGateTest {

    @Test
    fun `keeps the splash screen while the home data is loading`() {
        assertTrue(keepSplashOnScreen(initialDataLoaded = false, elapsedMillis = 0L))
        assertTrue(keepSplashOnScreen(initialDataLoaded = false, elapsedMillis = SPLASH_SCREEN_TIMEOUT_MS - 1))
    }

    @Test
    fun `dismisses the splash screen once the home data is loaded`() {
        assertFalse(keepSplashOnScreen(initialDataLoaded = true, elapsedMillis = 0L))
    }

    @Test
    fun `dismisses the splash screen after the timeout even when loading hangs`() {
        assertFalse(keepSplashOnScreen(initialDataLoaded = false, elapsedMillis = SPLASH_SCREEN_TIMEOUT_MS))
        assertFalse(keepSplashOnScreen(initialDataLoaded = false, elapsedMillis = Long.MAX_VALUE))
    }
}
