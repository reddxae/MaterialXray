package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.parser.FetchedSubscription
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionMetadata
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

    @Test
    fun fallbackFetchKeepsPrimaryPolicyWhenMirrorOmitsIt() {
        val existing = entity(fallbackUrl = "https://backup.example/sub", useFallbackUrl = true)
            .copy(requiresHardwareId = true)

        val fetched = FetchedSubscription(
            configs = listOf(ServerConfig(protocol = Protocol.VMESS, name = "Node", address = "example.com", port = 443, password = "secret")),
            metadata = SubscriptionMetadata(),
            resolvedUrl = "https://backup.example/sub",
        ).withFallbackPolicyPreserved(existing)

        assertEquals("https://backup.example/sub", fetched.metadata.fallbackUrl)
        assertEquals(true, fetched.metadata.requiresHardwareId)
    }

    @Test
    fun fallbackFetchPrefersMirrorPolicyWhenPresent() {
        val existing = entity(fallbackUrl = "https://backup.example/sub", useFallbackUrl = true)
            .copy(requiresHardwareId = true)

        val fetched = FetchedSubscription(
            configs = listOf(ServerConfig(protocol = Protocol.VMESS, name = "Node", address = "example.com", port = 443, password = "secret")),
            metadata = SubscriptionMetadata(
                fallbackUrl = "https://mirror.example/other",
                requiresHardwareId = false,
            ),
            resolvedUrl = "https://backup.example/sub",
        ).withFallbackPolicyPreserved(existing)

        assertEquals("https://mirror.example/other", fetched.metadata.fallbackUrl)
        assertEquals(true, fetched.metadata.requiresHardwareId)
    }

    private fun entity(fallbackUrl: String?, useFallbackUrl: Boolean) = SubscriptionEntity(
        name = "Sub",
        url = "https://example.com/sub",
        fallbackUrl = fallbackUrl,
        useFallbackUrl = useFallbackUrl,
    )
}
