package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.RoutingRule
import com.material.xray.model.SubscriptionRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionRoutingMapperTest {
    @Test
    fun `subscription routing round trip preserves provider config`() {
        val routing = SubscriptionRouting(
            rules = listOf(
                RoutingRule(
                    id = "provider-direct",
                    name = "Provider direct",
                    outboundTag = "direct",
                    domains = listOf("domain:example"),
                ),
            ),
            domainStrategy = "IPIfNonMatch",
            domainMatcher = "hybrid",
        )
        val entity = SubscriptionEntity(name = "Provider", url = "https://example.com/sub")
            .withSubscriptionRouting(routing)

        assertEquals(routing, entity.toSubscriptionRouting())
    }

    @Test
    fun `invalid persisted subscription routing is ignored`() {
        val entity = SubscriptionEntity(
            name = "Provider",
            url = "https://example.com/sub",
            providerRouting = "invalid",
        )

        assertNull(entity.toSubscriptionRouting())
    }
}
