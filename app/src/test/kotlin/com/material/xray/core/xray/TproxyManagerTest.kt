package com.material.xray.core.xray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TproxyManagerTest {
    @Test
    fun `activation routes marks locally before hooking output interception`() {
        val plan = plan()

        val command = TproxyManager.activationCommand(plan, APP_UID)

        assertTrue(command.indexOf("ip route replace local") < command.indexOf("-I OUTPUT 1"))
        assertTrue(command.indexOf("-I PREROUTING 1") < command.indexOf("-I OUTPUT 1"))
        assertTrue(command.contains("--mark 0xa000000/0xf000000"))
        assertTrue(command.contains("--tproxy-mark 0xa000001/0xffffffff"))
        assertTrue(command.contains("--on-ip 127.0.0.1"))
        assertTrue(command.contains("--on-port 48321"))
    }

    @Test
    fun `best effort guard cleanup cannot mask activation failures`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)

        assertTrue(command.contains("&& { iptables -t mangle -D OUTPUT -j MXG278b 2>/dev/null || true; } &&"))
        assertTrue(command.endsWith("{ ip6tables -t mangle -X MXG278b 2>/dev/null || true; }"))
    }

    @Test
    fun `output rules exempt xray app and bypass uids before assigning marks`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val exemptOutbound = command.indexOf("--mark 255/0xffffffff -j RETURN")
        val exemptApp = command.indexOf("--uid-owner $APP_UID -j RETURN")
        val exemptBypass = command.indexOf("--uid-owner 10020 -j RETURN")
        val groupMark = command.indexOf("--uid-owner 10030 -p tcp -j MARK")
        val groupReturn = command.indexOf("--mark 0xa000000/0xf000000 -j RETURN", groupMark)
        val profileMark = command.indexOf("--uid-owner 10000-99999 -p tcp -j MARK")

        assertTrue(exemptOutbound in 0..<exemptApp)
        assertTrue(exemptApp < exemptBypass)
        assertTrue(exemptBypass < groupMark)
        assertTrue(groupMark < groupReturn)
        assertTrue(groupReturn < profileMark)
        assertTrue(command.contains("--uid-owner 10000-99999 -j DROP"))
        assertTrue(command.split("--uid-owner $APP_UID -j RETURN").size - 1 == 2)
    }

    @Test
    fun `disabled IPv6 rejects managed apps instead of blackholing them`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)

        assertTrue(command.contains("ip6tables -t filter -I OUTPUT 1"))
        assertTrue(command.contains("-j REJECT --reject-with icmp6-no-route"))
        assertFalse(command.contains("ip6tables -t mangle -I OUTPUT 1"))
        assertFalse(command.contains("ip6tables -t mangle -I PREROUTING 1"))
        assertFalse(command.contains("ip -6 rule add"))
        assertFalse(command.contains("ip -6 route replace"))
    }

    @Test
    fun `disabled IPv6 keeps bypassed apps and xray itself on IPv6`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val appExempt = command.indexOf("ip6tables -t filter -A MXOA278b -m owner --uid-owner $APP_UID -j RETURN")
        val bypassExempt = command.indexOf("ip6tables -t filter -A MXOA278b -m owner --uid-owner 10020 -j RETURN")
        val reject = command.indexOf("-j REJECT --reject-with icmp6-no-route")

        assertTrue(appExempt in 0..<reject)
        assertTrue(bypassExempt in 0..<reject)
    }

    @Test
    fun `startup guard always covers IPv6`() {
        val command = TproxyManager.guardInstallCommand(plan(), APP_UID)

        assertTrue(command.contains("ip6tables -t mangle -I OUTPUT 1"))
    }

    @Test
    fun `fast update builds inactive slot before replacing the active jump`() {
        val command = TproxyManager.updateCommand(plan(), APP_UID, "a", "b")

        assertTrue(command.indexOf("-N MXOB") < command.indexOf("-R MXO"))
        assertTrue(command.indexOf("-R MXO") < command.indexOf("-F MXOA"))
    }

    @Test
    fun `cleanup only names owned chains and packet marks`() {
        val command = TproxyManager.cleanupCommand(plan().runtimeState, APP_UID)

        assertTrue(command.contains("MXO${APP_UID.toString(16)}"))
        assertTrue(command.contains("fwmark 0xa000000/0xf000000"))
        assertTrue(command.contains("ip6tables -t filter -D OUTPUT -j MXO278b"))
        assertFalse(command.contains("iptables-save"))
        assertFalse(command.contains("-F OUTPUT"))
        assertFalse(command.contains("route flush table"))
    }

    @Test
    fun `enabled IPv6 uses transparent proxying rather than rejection`() {
        val command = TproxyManager.activationCommand(plan(allowIpv6 = true), APP_UID)

        assertTrue(command.contains("ip -6 route replace local ::/0 dev lo table 300"))
        assertTrue(command.contains("ip6tables -t mangle -I PREROUTING 1"))
        assertTrue(command.contains("--on-ip 0.0.0.0"))
        assertTrue(command.contains("--on-ip ::"))
        assertTrue(command.contains("-m addrtype --dst-type LOCAL"))
        assertFalse(command.contains("ip6tables -t filter"))
        assertFalse(command.contains("icmp6-no-route"))
    }

    @Test
    fun `health verification covers marks UDP and live listeners`() {
        val command = TproxyManager.verifyCommand(plan().runtimeState, APP_UID)

        assertTrue(command.contains("--set-xmark 0xa000001/0xffffffff"))
        assertTrue(command.contains("-p udp -m mark --mark 0xa000001/0xffffffff"))
        assertTrue(command.contains("-d 127.0.0.0/8 -p udp --dport 48321 -j DROP"))
        assertTrue(command.contains("ss -lnu"))
        assertTrue(command.contains("ip6tables -t filter -C OUTPUT"))
        assertTrue(command.contains("--reject-with icmp6-no-route"))
        assertFalse(command.contains("ip6tables -t mangle -C OUTPUT"))
        assertFalse(command.contains("ip -6 rule show"))
    }

    private fun plan(allowIpv6: Boolean = false): TproxyTrafficPlan {
        val state = TproxyManager.createRuntimeState(
            routeTable = 300,
            groups = listOf(Long.MAX_VALUE to "tproxy-in-default", 7L to "app-in-7"),
            ports = listOf(48_321, 48_322),
            allowIpv6 = allowIpv6,
        )
        return TproxyTrafficPlan(
            runtimeState = state,
            groups = listOf(
                TproxyTrafficGroup(state.groups[0], emptySet(), isBase = true),
                TproxyTrafficGroup(state.groups[1], setOf(10_030)),
            ),
            bypassUids = setOf(APP_UID, 10_020),
            routeProfileIds = setOf(0),
            outboundMark = 255,
        )
    }

    private companion object {
        const val APP_UID = 10_123
    }
}
