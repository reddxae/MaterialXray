package com.material.xray.ui.home

import com.material.xray.data.db.entity.SubscriptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SubscriptionMetadataFormatterTest {
    private val zoneId = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), zoneId)

    @Test
    fun buildSubscriptionMetadataUiStateFormatsTrafficExpiryAndAnnouncement() {
        val state = buildSubscriptionMetadataUiState(
            subscription = SubscriptionEntity(
                name = "Sub",
                url = "https://example.com",
                subscriptionDownloadBytes = 5L * GIB,
                subscriptionTotalBytes = 10L * GIB,
                subscriptionExpireAt = Instant.parse("2026-05-10T00:00:00Z").epochSecond,
                autoUpdateIntervalHours = 24,
                announce = " Provider notice ",
            ),
            clock = clock,
            zoneId = zoneId,
        )

        assertTrue(state.hasMetadata)
        assertEquals("Provider notice", state.announcement)
        assertEquals("5.0 GB of 10 GB", state.traffic?.summary)
        assertEquals("10 GB", state.traffic?.quotaText)
        assertEquals(0.5f, state.traffic?.progress)
        assertEquals("expires on 10.05.2026", state.expiry?.inlineText)
        assertEquals("Auto update every day", state.updateIntervalText)
    }

    @Test
    fun expiryFormatterHidesFarFutureAndMarksPastValuesExpired() {
        assertNull(
            formatSubscriptionExpiryUiState(
                epochSeconds = Instant.parse("2028-01-01T00:00:00Z").epochSecond,
                clock = clock,
                zoneId = zoneId,
            ),
        )

        assertEquals(
            "Expired",
            formatSubscriptionExpiryUiState(
                epochSeconds = Instant.parse("2026-04-01T00:00:00Z").epochSecond,
                clock = clock,
                zoneId = zoneId,
            )?.standaloneText,
        )
    }

    @Test
    fun metadataTextSegmentsMarksStatusTokensOnly() {
        val segments = metadataTextSegments("Used ↓ 5 GB, expired")

        assertEquals(
            listOf(false, true, false, true),
            segments.map { it.emphasized },
        )
        assertEquals("↓", segments[1].value)
        assertEquals("expired", segments[3].value)
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
