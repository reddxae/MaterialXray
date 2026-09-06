package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDataManagerTest {
    @Test
    fun normalizeTrimsWhitespace() {
        val url = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"

        assertEquals(
            url,
            normalizeGeoDataUrl(" $url "),
        )
    }

    @Test
    fun stalenessTreatsMissingTimestampAsStale() {
        assertTrue(isGeoDataStale(updatedAtMillis = null, nowMillis = NOW_MILLIS))
        assertTrue(isGeoDataStale(updatedAtMillis = 0L, nowMillis = NOW_MILLIS))
    }

    @Test
    fun stalenessTriggersAtExactlyMaxAge() {
        assertTrue(isGeoDataStale(updatedAtMillis = NOW_MILLIS - GEO_DATA_MAX_AGE_MS, nowMillis = NOW_MILLIS))
    }

    @Test
    fun freshnessWithinMaxAgeIsNotStale() {
        assertFalse(isGeoDataStale(updatedAtMillis = NOW_MILLIS - GEO_DATA_MAX_AGE_MS + 1, nowMillis = NOW_MILLIS))
        assertFalse(isGeoDataStale(updatedAtMillis = NOW_MILLIS, nowMillis = NOW_MILLIS))
    }

    private companion object {
        const val NOW_MILLIS = 1_800_000_000_000L
    }
}
