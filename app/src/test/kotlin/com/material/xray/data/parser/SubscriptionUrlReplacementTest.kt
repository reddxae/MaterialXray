package com.material.xray.data.parser

import com.material.xray.model.SubscriptionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUrlReplacementTest {
    @Test
    fun `new-url replaces the subscription url`() {
        val metadata = SubscriptionMetadata(newUrl = " https://mynew-domain.com/3J3jrb4jfc ")

        assertEquals(
            "https://mynew-domain.com/3J3jrb4jfc",
            SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/sub"),
        )
    }

    @Test
    fun `new-url pointing at the current url is a no-op`() {
        val metadata = SubscriptionMetadata(newUrl = "https://old-domain.com/sub")

        assertNull(SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/sub"))
    }

    @Test
    fun `insecure new-url is rejected`() {
        val metadata = SubscriptionMetadata(newUrl = "http://mynew-domain.com/sub")

        assertNull(SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/sub"))
    }

    @Test
    fun `new-domain swaps the host and preserves the rest of the address`() {
        val metadata = SubscriptionMetadata(newDomain = "mynew-domain.com")

        assertEquals(
            "https://mynew-domain.com/3J3jrb4jfc?token=abc",
            SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/3J3jrb4jfc?token=abc"),
        )
    }

    @Test
    fun `new-domain keeps scheme and port`() {
        val metadata = SubscriptionMetadata(newDomain = "mynew-domain.com")

        assertEquals(
            "https://mynew-domain.com:8443/sub",
            SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com:8443/sub"),
        )
    }

    @Test
    fun `new-domain naming the current host is a no-op`() {
        val metadata = SubscriptionMetadata(newDomain = "OLD-DOMAIN.com")

        assertNull(SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/sub"))
    }

    @Test
    fun `invalid new-domain is rejected`() {
        val metadata = SubscriptionMetadata(newDomain = "not a host")

        assertNull(SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/sub"))
    }

    @Test
    fun `new-url wins over new-domain`() {
        val metadata = SubscriptionMetadata(
            newUrl = "https://new-url.example/sub",
            newDomain = "new-domain.example",
        )

        assertEquals(
            "https://new-url.example/sub",
            SubscriptionUrlReplacement.resolve(metadata, "https://old-domain.com/sub"),
        )
    }

    @Test
    fun `absent directives resolve to null`() {
        assertNull(SubscriptionUrlReplacement.resolve(null, "https://old-domain.com/sub"))
        assertNull(SubscriptionUrlReplacement.resolve(SubscriptionMetadata(), "https://old-domain.com/sub"))
    }
}
