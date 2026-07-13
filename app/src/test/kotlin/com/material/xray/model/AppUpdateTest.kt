package com.material.xray.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun newerMinorReleaseIsDetected() {
        assertTrue(isReleaseNewer(latestTag = "v0.10.0", currentVersion = "0.9.0"))
    }

    @Test
    fun olderReleaseIsIgnored() {
        assertFalse(isReleaseNewer(latestTag = "v0.4.9", currentVersion = "0.5.0"))
    }

    @Test
    fun equivalentReleaseWithMissingComponentsIsIgnored() {
        assertFalse(isReleaseNewer(latestTag = "v1.0", currentVersion = "1.0.0"))
    }

    @Test
    fun additionalNonZeroComponentIsNewer() {
        assertTrue(isReleaseNewer(latestTag = "v1.0.1", currentVersion = "1"))
    }

    @Test
    fun malformedReleaseTagIsIgnored() {
        assertFalse(isReleaseNewer(latestTag = "latest", currentVersion = "0.5.0"))
    }

    @Test
    fun updateCheckIsNotDueBeforeMinimumInterval() {
        assertFalse(
            isUpdateCheckDue(
                lastCheckAtMillis = 1_000L,
                nowMillis = 3_600_999L,
                minimumIntervalMillis = 3_600_000L,
            ),
        )
    }

    @Test
    fun updateCheckIsDueAtMinimumInterval() {
        assertTrue(
            isUpdateCheckDue(
                lastCheckAtMillis = 1_000L,
                nowMillis = 3_601_000L,
                minimumIntervalMillis = 3_600_000L,
            ),
        )
    }
}
