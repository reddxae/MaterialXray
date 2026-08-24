package com.material.xray.core.xray

import org.junit.Assert.assertEquals
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
}
