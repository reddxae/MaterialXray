package com.material.xray.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingRuleTest {
    private fun rule(
        domains: List<String> = emptyList(),
        ips: List<String> = emptyList(),
        port: String? = null,
        protocols: List<String> = emptyList(),
    ) = RoutingRule(
        id = "test",
        name = "Test",
        outboundTag = "direct",
        domains = domains,
        ips = ips,
        port = port,
        protocols = protocols,
    )

    @Test
    fun ruleWithoutAnyConditionMatchesAllTraffic() {
        assertTrue(rule().matchesAllTraffic())
    }

    @Test
    fun blankConditionsAreTreatedAsAbsent() {
        assertTrue(
            rule(
                domains = listOf("", "  "),
                ips = listOf(" "),
                port = "   ",
                protocols = listOf(""),
            ).matchesAllTraffic(),
        )
    }

    @Test
    fun anySingleConditionStopsTheRuleFromMatchingAllTraffic() {
        assertFalse(rule(domains = listOf("domain:ru")).matchesAllTraffic())
        assertFalse(rule(ips = listOf("geoip:ru")).matchesAllTraffic())
        assertFalse(rule(port = "443").matchesAllTraffic())
        assertFalse(rule(protocols = listOf("tls")).matchesAllTraffic())
    }

    @Test
    fun catalogDefaultsNeverMatchAllTraffic() {
        RoutingRuleCatalog.defaults().forEach { default ->
            assertFalse(default.id, default.matchesAllTraffic())
        }
    }
}
