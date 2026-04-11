package com.materialxray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoDataManagerTest {
    private val geoipUrl =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    private val geositeUrl =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

    @Test
    fun normalizeTrimsWhitespace() {
        assertEquals(
            geoipUrl,
            normalizeGeoDataUrl(" $geoipUrl "),
        )
    }

    @Test
    fun normalizeLeavesDirectFileUrlIntact() {
        assertEquals(
            geositeUrl,
            normalizeGeoDataUrl(geositeUrl),
        )
    }
}
