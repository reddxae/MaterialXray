package com.material.xray.ui.home

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.ProviderRoutingAvailability
import com.material.xray.data.repository.withSubscriptionAppRouting
import com.material.xray.data.repository.withSubscriptionRouting
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import com.material.xray.model.SubscriptionRouting
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionRoutingDataTest {
    @Test
    fun `manual apply excludes only the routing type managed by selected provider`() {
        val subscription = SubscriptionEntity(
            name = "Import source",
            url = "https://example.com/sub",
        ).withSubscriptionAppRouting(
            SubscriptionAppRouting(
                packageNames = listOf("com.example.app"),
                mode = SubscriptionAppRoutingMode.Direct,
            ),
        ).withSubscriptionRouting(SubscriptionRouting(emptyList()))

        val routing = subscription.manualRoutingData(
            policy = RoutingPolicyControl.SubscriptionProvider,
            selectedProvider = ProviderRoutingAvailability(
                providerName = "Selected provider",
                appRoutingProvided = true,
                xrayRoutingProvided = false,
            ),
        )

        assertNull(routing.appRouting)
        assertNotNull(routing.routing)
    }
}
