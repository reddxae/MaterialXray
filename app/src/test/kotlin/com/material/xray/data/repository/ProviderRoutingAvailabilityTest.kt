package com.material.xray.data.repository

import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import com.material.xray.model.SubscriptionRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRoutingAvailabilityTest {
    @Test
    fun `subscription without routing headers manages neither routing type`() {
        val subscription = SubscriptionEntity(
            id = 3,
            name = "ANYX",
            url = "https://example.com/sub",
        )

        val availability = selectedProviderRoutingAvailability(
            selectedServerId = 50,
            servers = listOf(
                ServerEntity(
                    id = 50,
                    subscriptionId = subscription.id,
                    name = "Server",
                    protocol = "VLESS",
                    address = "example.com",
                    port = 443,
                    configJson = "{}",
                ),
            ),
            subscriptions = listOf(subscription),
        )

        assertEquals("ANYX", availability?.providerName)
        assertFalse(availability?.appRoutingProvided == true)
        assertFalse(availability?.xrayRoutingProvided == true)
    }

    @Test
    fun `routing availability is tracked separately for rules and apps`() {
        val subscription = SubscriptionEntity(
            name = "Provider",
            url = "https://example.com/sub",
        )
        val appOnly = subscription.withSubscriptionAppRouting(
            SubscriptionAppRouting(
                packageNames = listOf("com.example.app"),
                mode = SubscriptionAppRoutingMode.Direct,
            ),
        ).providerRoutingAvailability()
        val rulesOnly = subscription.withSubscriptionRouting(
            SubscriptionRouting(emptyList()),
        ).providerRoutingAvailability()

        assertTrue(appOnly.appRoutingProvided)
        assertFalse(appOnly.xrayRoutingProvided)
        assertFalse(rulesOnly.appRoutingProvided)
        assertTrue(rulesOnly.xrayRoutingProvided)
    }
}
