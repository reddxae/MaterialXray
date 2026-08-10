package com.material.xray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashScreenGateTest {

    @Test
    fun `keeps the splash screen while the home data is loading`() {
        assertTrue(keepSplashOnScreen(homeDataLoaded = false, elapsedMillis = 0L))
        assertTrue(keepSplashOnScreen(homeDataLoaded = false, elapsedMillis = SPLASH_SCREEN_TIMEOUT_MS - 1))
    }

    @Test
    fun `dismisses the splash screen once the home data is loaded`() {
        assertFalse(keepSplashOnScreen(homeDataLoaded = true, elapsedMillis = 0L))
    }

    @Test
    fun `dismisses the splash screen after the timeout even when loading hangs`() {
        assertFalse(keepSplashOnScreen(homeDataLoaded = false, elapsedMillis = SPLASH_SCREEN_TIMEOUT_MS))
        assertFalse(keepSplashOnScreen(homeDataLoaded = false, elapsedMillis = Long.MAX_VALUE))
    }
}
