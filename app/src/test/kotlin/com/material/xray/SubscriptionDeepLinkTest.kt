package com.material.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionDeepLinkTest {

    @Test
    fun `extracts subscription URL from mxray add link`() {
        assertEquals(
            "https://sub.pierdoling.org/3uFMV9SvEcdJeFsw",
            subscriptionLinkFromDeepLink("mxray://add/https://sub.pierdoling.org/3uFMV9SvEcdJeFsw"),
        )
    }

    @Test
    fun `preserves query parameters in subscription URL`() {
        assertEquals(
            "https://example.com/sub?token=one&client=mxray",
            subscriptionLinkFromDeepLink("mxray://add/https://example.com/sub?token=one&client=mxray"),
        )
    }

    @Test
    fun `rejects unsupported deep links`() {
        assertNull(subscriptionLinkFromDeepLink("mxray://open/https://example.com/sub"))
        assertNull(subscriptionLinkFromDeepLink("mxray://add/ftp://example.com/sub"))
        assertNull(subscriptionLinkFromDeepLink("https://example.com/sub"))
    }
}
