package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionAppRoutingTest {
    @Test
    fun `assignment mode keeps mode for listed packages only without invert`() {
        val routing = SubscriptionAppRouting(
            packageNames = listOf("com.example.one"),
            mode = SubscriptionAppRoutingMode.Direct,
        ).normalized()!!

        assertEquals(SubscriptionAppRoutingMode.Direct, routing.assignmentModeFor("com.example.one"))
        assertNull(routing.assignmentModeFor("com.example.unlisted"))
    }

    @Test
    fun `inverted bypass list sends listed packages to proxy and rest direct`() {
        val routing = SubscriptionAppRouting(
            packageNames = listOf("com.example.one"),
            mode = SubscriptionAppRoutingMode.Direct,
            inverted = true,
        ).normalized()!!

        assertEquals(SubscriptionAppRoutingMode.DefaultSelected, routing.assignmentModeFor("com.example.one"))
        assertEquals(SubscriptionAppRoutingMode.Direct, routing.assignmentModeFor("com.example.unlisted"))
    }

    @Test
    fun `inverted proxy list sends listed packages direct and rest proxy`() {
        val routing = SubscriptionAppRouting(
            packageNames = listOf("com.example.one"),
            mode = SubscriptionAppRoutingMode.DefaultSelected,
            inverted = true,
        ).normalized()!!

        assertEquals(SubscriptionAppRoutingMode.Direct, routing.assignmentModeFor("com.example.one"))
        assertEquals(
            SubscriptionAppRoutingMode.DefaultSelected,
            routing.assignmentModeFor("com.example.unlisted"),
        )
    }

    @Test
    fun `inverted default outbound mode sends listed packages direct`() {
        val routing = SubscriptionAppRouting(
            packageNames = listOf("com.example.one"),
            mode = SubscriptionAppRoutingMode.DefaultOutbound,
            inverted = true,
        ).normalized()!!

        assertEquals(SubscriptionAppRoutingMode.Direct, routing.assignmentModeFor("com.example.one"))
        assertEquals(
            SubscriptionAppRoutingMode.DefaultOutbound,
            routing.assignmentModeFor("com.example.unlisted"),
        )
    }

    @Test
    fun `normalized keeps inverted flag`() {
        val routing = SubscriptionAppRouting(
            packageNames = listOf(" com.example.one "),
            mode = SubscriptionAppRoutingMode.Direct,
            inverted = true,
        ).normalized()!!

        assertEquals(listOf("com.example.one"), routing.packageNames)
        assertEquals(true, routing.inverted)
    }
}
