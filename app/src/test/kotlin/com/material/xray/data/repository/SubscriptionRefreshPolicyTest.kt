package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshPolicyTest {
    @Test
    fun invalidatedSubscriptionIsDueEvenWhenAutomaticUpdatesAreDisabled() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 0,
            autoUpdateIntervalHours = 0,
        )

        assertTrue(subscription.isDueForRefresh(nowMillis = 1_000))
    }

    @Test
    fun currentManualSubscriptionIsNotDue() {
        val subscription = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com",
            lastUpdated = 1_000,
            autoUpdateIntervalHours = 0,
        )

        assertFalse(subscription.isDueForRefresh(nowMillis = 2_000))
    }
}
