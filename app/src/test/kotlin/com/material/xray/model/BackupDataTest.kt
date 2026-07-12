package com.material.xray.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old backup leaves subscription JSON preference unset`() {
        val backup = json.decodeFromString<BackupData>(
            """
            {
              "subscriptions": [{"name": "Provider", "url": "https://example.com/sub"}],
              "bypassedApps": [],
              "settings": {"subscription_prefer_json": "false"}
            }
            """.trimIndent(),
        )

        assertNull(backup.subscriptions.single().preferJson)
    }

    @Test
    fun `backup round trip preserves per-subscription JSON preference`() {
        val backup = BackupData(
            subscriptions = listOf(
                BackupData.BackupSubscription(
                    name = "Provider",
                    url = "https://example.com/sub",
                    preferJson = false,
                ),
            ),
            bypassedApps = emptyList(),
            settings = emptyMap(),
        )

        val restored = json.decodeFromString<BackupData>(json.encodeToString(backup))

        assertEquals(false, restored.subscriptions.single().preferJson)
    }

    @Test
    fun `backup round trip preserves xray performance settings`() {
        val settings = mapOf(
            "xray_buffer_size_kib" to "1024",
            "tun_mtu" to "1400",
        )
        val backup = BackupData(
            subscriptions = emptyList(),
            bypassedApps = emptyList(),
            settings = settings,
        )

        val restored = json.decodeFromString<BackupData>(json.encodeToString(backup))

        assertEquals(settings, restored.settings)
    }

    @Test
    fun `backup round trip preserves provider routing`() {
        val routing = SubscriptionRouting(
            rules = listOf(
                RoutingRule(
                    id = "provider-block",
                    name = "Block ads",
                    outboundTag = "block",
                    domains = listOf("geosite:category-ads-all"),
                ),
            ),
            domainStrategy = "IPIfNonMatch",
            domainMatcher = "hybrid",
        )
        val backup = BackupData(
            subscriptions = listOf(
                BackupData.BackupSubscription(
                    name = "Provider",
                    url = "https://example.com/sub",
                    routing = routing,
                ),
            ),
            bypassedApps = emptyList(),
            settings = emptyMap(),
        )

        val restored = json.decodeFromString<BackupData>(json.encodeToString(backup))

        assertEquals(routing, restored.subscriptions.single().routing)
    }
}
