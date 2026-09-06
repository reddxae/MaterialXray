package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionFallbackUrlTest {
    @Test
    fun returnsNullWhenFallbackIsDisabled() {
        val entity = entity(fallbackUrl = "https://backup.example/sub", useFallbackUrl = false)

        assertNull(entity.fallbackRefreshUrl("https://example.com/sub"))
    }

    @Test
    fun returnsNullWhenProviderNeverAdvertisedFallback() {
        val entity = entity(fallbackUrl = null, useFallbackUrl = true)

        assertNull(entity.fallbackRefreshUrl("https://example.com/sub"))
    }

    @Test
    fun returnsNullWhenFallbackMatchesPrimaryUrl() {
        val entity = entity(fallbackUrl = "https://example.com/sub", useFallbackUrl = true)

        assertNull(entity.fallbackRefreshUrl(" https://example.com/sub "))
    }

    @Test
    fun returnsTrimmedFallbackUrl() {
        val entity = entity(fallbackUrl = " https://backup.example/sub ", useFallbackUrl = true)

        assertEquals("https://backup.example/sub", entity.fallbackRefreshUrl("https://example.com/sub"))
    }

    private fun entity(fallbackUrl: String?, useFallbackUrl: Boolean) = SubscriptionEntity(
        name = "Sub",
        url = "https://example.com/sub",
        fallbackUrl = fallbackUrl,
        useFallbackUrl = useFallbackUrl,
    )
}
