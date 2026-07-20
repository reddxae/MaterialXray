package com.material.xray.data.repository

import com.material.xray.data.db.entity.ServerEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRefreshCoordinatorTest {
    @Test
    fun `selected server without replacement is removed`() {
        assertEquals(
            SelectedServerRefreshOutcome.Removed,
            selectedServerRefreshOutcome(
                selectedServer = server(id = 10, subscriptionId = 2),
                refreshedSubscriptionId = 2,
                refreshResult = refreshResult(replacements = emptyMap()),
            ),
        )
    }

    @Test
    fun `selected server replacement updates id`() {
        assertEquals(
            SelectedServerRefreshOutcome.Replaced(20),
            selectedServerRefreshOutcome(
                selectedServer = server(id = 10, subscriptionId = 2),
                refreshedSubscriptionId = 2,
                refreshResult = refreshResult(replacements = mapOf(10L to 20L)),
            ),
        )
    }

    @Test
    fun `refreshing unrelated subscription leaves selection unchanged`() {
        assertEquals(
            SelectedServerRefreshOutcome.Unchanged,
            selectedServerRefreshOutcome(
                selectedServer = server(id = 10, subscriptionId = 2),
                refreshedSubscriptionId = 3,
                refreshResult = refreshResult(replacements = emptyMap()),
            ),
        )
    }

    @Test
    fun `replacement retaining id leaves selection unchanged`() {
        assertEquals(
            SelectedServerRefreshOutcome.Unchanged,
            selectedServerRefreshOutcome(
                selectedServer = server(id = 10, subscriptionId = 2),
                refreshedSubscriptionId = 2,
                refreshResult = refreshResult(replacements = mapOf(10L to 10L)),
            ),
        )
    }

    private fun server(id: Long, subscriptionId: Long) = ServerEntity(
        id = id,
        subscriptionId = subscriptionId,
        name = "Server",
        protocol = "VLESS",
        address = "example.com",
        port = 443,
        configJson = "{}",
        sortOrder = 0,
    )

    private fun refreshResult(replacements: Map<Long, Long>) = SubscriptionRepository.RefreshResult(
        subscriptionId = 2,
        serverIdByConfigJson = emptyMap(),
        serverIdReplacements = replacements,
        appRouting = null,
        routing = null,
    )
}
