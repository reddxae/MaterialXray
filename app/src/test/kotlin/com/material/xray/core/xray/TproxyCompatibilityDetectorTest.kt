package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TproxyCompatibilityDetectorTest {
    @Test
    fun `dual stack probe exercises production firewall hooks and policy routing`() {
        val command = TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = true)

        assertTrue(command.contains("-p tcp -m mark --mark 0xb000001/0xffffffff -j TPROXY"))
        assertTrue(command.contains("-p udp -m mark --mark 0xb000001/0xffffffff -j TPROXY"))
        assertTrue(command.contains("iptables -t mangle -I PREROUTING 1 -j MXPabc1234P"))
        assertTrue(command.contains("iptables -t mangle -I OUTPUT 1 -j MXPabc1234O"))
        assertTrue(command.contains("ip6tables -t mangle -I PREROUTING 1 -j MXPabc1236P"))
        assertTrue(command.contains("ip6tables -t mangle -I OUTPUT 1 -j MXPabc1236O"))
        assertTrue(command.contains("iptables -t mangle -A MXPabc1234O -m addrtype --dst-type LOCAL"))
        assertTrue(command.contains("ip6tables -t mangle -A MXPabc1236O -m addrtype --dst-type LOCAL"))
        assertTrue(command.contains("ip route get 192.0.2.1 mark"))
        assertTrue(command.contains("ip -6 route get 2001:db8::1 mark"))
        assertTrue(command.contains("ip6tables -t mangle -X MXPabc1236P"))
        assertTrue(command.contains("fail cleanup"))
        assertFalse(command.contains("curl"))
        assertFalse(command.contains("ping"))
    }

    @Test
    fun `IPv4 only probe covers loopback binding IPv6 blocking and listener checks`() {
        val command = TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = false)

        assertTrue(command.contains("--on-ip 127.0.0.1"))
        assertTrue(command.contains("-d 127.0.0.0/8 -p tcp --dport 9 -j DROP"))
        assertTrue(command.contains("ip6tables -t mangle -I OUTPUT 1 -j MXPabc1236O"))
        assertTrue(command.contains("ip6tables -t filter -I OUTPUT 1 -j MXPabc1236F"))
        assertTrue(command.contains("-j REJECT --reject-with icmp6-no-route"))
        assertTrue(command.contains("--uid-owner 0-1"))
        assertTrue(command.contains("ss -lnt >/dev/null && ss -lnu >/dev/null"))
        assertFalse(command.contains("addrtype"))
        assertFalse(command.contains("-m socket"))
    }

    @Test
    fun `probe mark cannot be captured by the production policy rule`() {
        val command = TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = true)

        assertTrue(command.contains("0xb000000"))
        assertFalse(command.contains("0xa000000"))
        assertFalse(command.contains("0xa000001"))
    }

    @Test
    fun `probe rule outruns Android policy routing and the production rule`() {
        val priorities = (0..0xfff).map { value ->
            val suffix = value.toString(16).padStart(3, '0')
            val command = TproxyCompatibilityDetector.probeCommand(suffix, allowIpv6 = false)
            Regex("ip rule add fwmark \\S+ table \\d+ pref (\\d+)").find(command)!!.groupValues[1].toInt()
        }

        assertTrue(priorities.all { it > TproxyManager.RULE_PRIORITY })
        assertTrue(priorities.all { it < ANDROID_INTERFACE_RULE_PRIORITY })
    }

    @Test
    fun `collision check accepts only the apps owned output chain`() {
        val command = TproxyCompatibilityDetector.markCollisionCommand(10_123)

        assertTrue(command.contains("fwmark 0xa000000/0xf000000"))
        assertTrue(command.contains("-C OUTPUT -j MXO278b"))
        assertTrue(command.contains("exit 42"))
    }

    @Test
    fun `overlap detection catches broader narrower and exact rules`() {
        val output = """
            100: from all fwmark 0xa000000/0xff000000 lookup 1
            101: from all fwmark 0xa000001/0xffffffff lookup 2
            102: from all fwmark 0xb000000/0xff000000 lookup 3
            103: from all fwmark 0xa000000/0xf000000 lookup 4
        """.trimIndent()

        val overlaps = overlappingFwmarkRules(
            output,
            TproxyCompatibilityDetector.MARK_PREFIX,
            TproxyCompatibilityDetector.MARK_MASK,
        )

        assertEquals(listOf(100, 101, 103), overlaps.map { it.priority })
    }

    @Test
    fun `normal Android low-bit fwmark rules do not conflict with generated marks`() {
        val output = """
            9999: from all fwmark 0x20000/0xfffff lookup 1027
            10000: from all fwmark 0xc0000/0xd0000 lookup 99
        """.trimIndent()

        val overlaps = overlappingFwmarkRules(
            output,
            TproxyCompatibilityDetector.MARK_PREFIX,
            TproxyCompatibilityDetector.MARK_MASK,
        )

        assertTrue(overlaps.isEmpty())
    }

    @Test
    fun `IPv6-only failure preserves IPv4 TPROXY support`() {
        val ipv4 = TproxyCompatibility.Supported(ipv6 = false)

        val result = resolveDualStackCompatibility(
            ipv4,
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv6Unavailable),
        )

        assertEquals(ipv4, result)
    }

    @Test
    fun `only kernel capability verdicts count as a real statement about the device`() {
        assertTrue(TproxyCompatibility.Supported(ipv6 = true).isConclusive())
        assertTrue(
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv6Unavailable).isConclusive(),
        )
        assertTrue(
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.Ipv6BlockingUnavailable).isConclusive(),
        )
        assertFalse(TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.RootUnavailable).isConclusive())
        assertFalse(TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.CommandTimedOut).isConclusive())
        assertFalse(TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.MarkNamespaceConflict).isConclusive())
        assertFalse(TproxyCompatibility.Unknown.isConclusive())
    }

    @Test
    fun `cache round trips conclusive compatibility verdicts`() {
        val verdicts = listOf(
            TproxyCompatibility.Supported(ipv6 = true),
            TproxyCompatibility.Supported(ipv6 = false),
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv4Unavailable),
        )

        verdicts.forEach { verdict ->
            assertEquals(verdict, decodeCachedTproxyCompatibility(encodeCachedTproxyCompatibility(verdict)))
        }
    }

    @Test
    fun `cache rejects transient and malformed compatibility verdicts`() {
        assertEquals(
            null,
            encodeCachedTproxyCompatibility(
                TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.CommandTimedOut),
            ),
        )
        assertEquals(null, decodeCachedTproxyCompatibility("0|supported|1"))
        assertEquals(null, decodeCachedTproxyCompatibility("1|supported|maybe"))
        assertEquals(null, decodeCachedTproxyCompatibility("1|unsupported|CommandTimedOut"))
    }

    private companion object {
        const val ANDROID_INTERFACE_RULE_PRIORITY = 14_999
    }
}
