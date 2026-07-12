package com.material.xray.ui.home

import com.material.xray.R
import com.material.xray.data.db.entity.SubscriptionEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            text = EnglishSubscriptionMetadataText,
            clock = clock,
            zoneId = zoneId,
        )

        assertTrue(state.hasMetadata)
        assertEquals("Provider notice", state.announcement)
        assertEquals("5.0 GB of 10 GB", state.traffic?.summary)
        assertEquals("10 GB", state.traffic?.quotaText)
        assertEquals(0.5f, state.traffic?.progress)
        assertEquals("expires on May 10, 2026", state.expiry?.inlineText)
        assertEquals("Auto update every day", state.updateIntervalText)
    }

    @Test
    fun expiryFormatterHidesFarFutureAndMarksPastValuesExpired() {
        assertNull(
            formatSubscriptionExpiryUiState(
                epochSeconds = Instant.parse("2028-01-01T00:00:00Z").epochSecond,
                text = EnglishSubscriptionMetadataText,
                clock = clock,
                zoneId = zoneId,
            ),
        )

        assertEquals(
            "Expired",
            formatSubscriptionExpiryUiState(
                epochSeconds = Instant.parse("2026-04-01T00:00:00Z").epochSecond,
                text = EnglishSubscriptionMetadataText,
                clock = clock,
                zoneId = zoneId,
            )?.standaloneText,
        )
    }

    @Test
    fun metadataTextSegmentsMarksStatusTokensOnly() {
        val segments = metadataTextSegments("Used ↓ 5 GB, expired", expiredStatusText = "expired")

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

    private object EnglishSubscriptionMetadataText : SubscriptionMetadataText {
        override val locale: Locale = Locale.US

        override fun getString(resourceId: Int, vararg arguments: Any): String {
            val value = when (resourceId) {
                R.string.home_subscription_unlimited_traffic -> "∞ traffic"
                R.string.home_subscription_unlimited_traffic_downloaded -> "∞ traffic, ↓ %1\$s"
                R.string.home_subscription_used_of_total -> "%1\$s of %2\$s"
                R.string.home_subscription_downloaded -> "↓ %1\$s"
                R.string.home_subscription_downloaded_expires_on -> "↓ %1\$s, expires on %2\$s"
                R.string.home_subscription_downloaded_expired -> "↓ %1\$s, expired"
                R.string.home_subscription_expired_inline -> "expired"
                R.string.home_subscription_expired_standalone -> "Expired"
                R.string.home_subscription_expires_on_inline -> "expires on %1\$s"
                R.string.home_subscription_expires_on_standalone -> "Expires on %1\$s"
                R.string.home_auto_update_manual -> "Manual update only"
                R.string.home_gigabytes -> "%1\$s GB"
                else -> error("Unexpected string resource: $resourceId")
            }
            return value.formatWith(arguments)
        }

        override fun getQuantityString(resourceId: Int, quantity: Int, vararg arguments: Any): String {
            val value = when (resourceId) {
                R.plurals.home_auto_update_every_hours -> if (quantity == 1) {
                    "Auto update every hour"
                } else {
                    "Auto update every %1\$d hours"
                }
                R.plurals.home_auto_update_every_days -> if (quantity == 1) {
                    "Auto update every day"
                } else {
                    "Auto update every %1\$d days"
                }
                else -> error("Unexpected plurals resource: $resourceId")
            }
            return value.formatWith(arguments)
        }

        private fun String.formatWith(arguments: Array<out Any>): String = if (arguments.isEmpty()) this else String.format(locale, this, *arguments)
    }
}
