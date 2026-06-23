package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRequestIdentityTest {

    @Test
    fun `parseSubscriptionHeaders parses name value pairs`() {
        val headers = parseSubscriptionHeaders(
            """
            X-Hwid: abc123
            User-Agent: Happ/3.23.0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                SubscriptionHeader("X-Hwid", "abc123"),
                SubscriptionHeader("User-Agent", "Happ/3.23.0"),
            ),
            headers,
        )
    }

    @Test
    fun `parseSubscriptionHeaders keeps colons inside the value`() {
        val headers = parseSubscriptionHeaders("X-Time: 12:30:00")

        assertEquals(listOf(SubscriptionHeader("X-Time", "12:30:00")), headers)
    }

    @Test
    fun `parseSubscriptionHeaders skips blank and malformed lines`() {
        val headers = parseSubscriptionHeaders(
            """

            not-a-header
            : missing-name
            X-Valid: ok
            """.trimIndent(),
        )

        assertEquals(listOf(SubscriptionHeader("X-Valid", "ok")), headers)
    }

    @Test
    fun `fromValue falls back to default for unknown values`() {
        assertEquals(SubscriptionUserAgentMode.HAPP, SubscriptionUserAgentMode.fromValue("happ"))
        assertEquals(SubscriptionUserAgentMode.default, SubscriptionUserAgentMode.fromValue("nope"))
        assertEquals(SubscriptionUserAgentMode.default, SubscriptionUserAgentMode.fromValue(null))
    }
}
