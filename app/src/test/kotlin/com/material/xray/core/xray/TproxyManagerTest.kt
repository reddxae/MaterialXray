package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun `activation leaves startup guard installed until verification`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)

        assertFalse(command.contains("MXG278b"))
    }

    @Test
    fun `restore activation checks support and cleans partial state before fallback`() {
        val command = TproxyManager.activationRestoreCommand(plan(), APP_UID)

        assertTrue(command.contains("command -v iptables-restore"))
        assertTrue(command.contains("command -v ip6tables-restore"))
        assertTrue(command.contains("iptables-restore --help"))
        assertTrue(command.contains("ip6tables-restore --help"))
        assertTrue(command.contains("iptables-restore --noflush"))
        assertTrue(command.contains("ip6tables-restore --noflush"))
        assertTrue(command.contains("status=\$?"))
        assertTrue(command.contains("iptables -t mangle -D OUTPUT -j MXO278b"))
        assertTrue(command.contains("exit \$status"))
    }

    @Test
    fun `activation falls back to individual commands when restore fails`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                2 -> RootShell.Result(127, "", "restore unavailable")
                else -> RootShell.Result(0, "", "")
            }
        }

        val result = manager.activate(plan())

        assertTrue(result.success)
        assertEquals(3, commands.size)
        assertTrue(commands[1].contains("iptables-restore"))
        assertTrue(commands[2].contains("iptables -t mangle -N"))
    }

    @Test
    fun `activation does not fall back over a failed restore transaction`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                else -> RootShell.Result(1, "", "restore failed")
            }
        }

        val result = manager.activate(plan())

        assertFalse(result.success)
        assertEquals(2, commands.size)
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
    fun `output rules route resolver DNS through the base inbound`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val bypass = command.indexOf("--uid-owner 10020 -j RETURN")
        val udpDns = command.indexOf("-p udp --dport 53 -j MARK --set-xmark 0xa000001/0xffffffff")
        val appRoute = command.indexOf("--uid-owner 10030 -p tcp -j MARK")

        assertTrue(bypass in 0..<udpDns)
        assertTrue(udpDns < appRoute)
        assertTrue(command.contains("-p tcp --dport 53 -j MARK --set-xmark 0xa000001/0xffffffff"))
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
    fun `tether guard verification covers both families and forwarding hooks`() {
        val command = TproxyManager.guardVerifyCommand(APP_UID, plan(tetherUpstreamInterface = "wlan0").runtimeState)

        assertTrue(command.contains("iptables -t mangle -C OUTPUT -j MXG278b"))
        assertTrue(command.contains("ip6tables -t mangle -C OUTPUT -j MXG278b"))
        assertTrue(command.contains("iptables -t filter -C INPUT -j MXG278b"))
        assertTrue(command.contains("ip6tables -t filter -C FORWARD -j MXG278b"))
    }

    @Test
    fun `restore guard checks support and retains individual command fallback`() {
        val command = TproxyManager.guardRestoreCommand(plan(), APP_UID)

        assertTrue(command.contains("command -v iptables-restore"))
        assertTrue(command.contains("command -v ip6tables-restore"))
        assertTrue(command.contains("iptables-restore --noflush"))
        assertTrue(command.contains("ip6tables-restore --noflush"))
        assertTrue(command.contains("iptables -t mangle -D OUTPUT -j MXG278b"))
    }

    @Test
    fun `failed guard fallback removes partial guards from both families`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1 -> RootShell.Result(127, "", "restore unavailable")
                2 -> RootShell.Result(1, "", "IPv6 setup failed")
                else -> RootShell.Result(0, "", "")
            }
        }

        val result = manager.installGuard(plan())

        assertFalse(result.success)
        assertEquals(3, commands.size)
        assertTrue(commands[2].contains("iptables -t mangle -D OUTPUT -j MXG278b"))
        assertTrue(commands[2].contains("ip6tables -t mangle -D OUTPUT -j MXG278b"))
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
        assertEquals(2, command.split("-p udp --dport 53 -j MARK --set-xmark 0xa000001/0xffffffff").size - 1)
        assertFalse(command.contains("ip6tables -t filter"))
        assertFalse(command.contains("icmp6-no-route"))
    }

    @Test
    fun `tether traffic uses base inbound without intercepting upstream or LAN`() {
        val command = TproxyManager.activationCommand(plan(tetherUpstreamInterface = "wlan0"), APP_UID)
        val upstreamReturn = command.indexOf("-i wlan0 -j RETURN")
        val dnsCapture = command.indexOf("--dport 53 -j TPROXY --on-ip 0.0.0.0 --on-port 48321")
        val lanReturn = command.indexOf("-d 192.168.0.0/16 -j RETURN")
        val publicCapture = command.indexOf("-p tcp -j TPROXY --on-ip 0.0.0.0 --on-port 48321")

        assertTrue(upstreamReturn in 0..<dnsCapture)
        assertTrue(dnsCapture < lanReturn)
        assertTrue(lanReturn < publicCapture)
        assertTrue(command.contains("ip6tables -t filter -I INPUT 1 -j MXP278b"))
        assertTrue(command.contains("ip6tables -t filter -I FORWARD 1 -j MXP278b"))
        assertTrue(command.contains("ip6tables -t filter -A MXP278b -j REJECT --reject-with icmp6-no-route"))
    }

    @Test
    fun `tether startup guard blocks public forwarding until activation`() {
        val command = TproxyManager.guardInstallCommand(plan(tetherUpstreamInterface = "wlan0"), APP_UID)

        assertTrue(command.contains("iptables -t filter -I INPUT 1 -j MXG278b"))
        assertTrue(command.contains("iptables -t filter -I FORWARD 1 -j MXG278b"))
        assertTrue(command.contains("iptables -t filter -A MXG278b -i wlan0 -j RETURN"))
        assertTrue(command.contains("iptables -t filter -A MXG278b -j DROP"))
    }

    @Test
    fun `tether traffic does not bypass private destinations when LAN bypass is disabled`() {
        val command = TproxyManager.activationCommand(
            plan(tetherUpstreamInterface = "wlan0", tetherBypassLan = false),
            APP_UID,
        )

        assertFalse(command.contains("MXP278b -d 192.168.0.0/16 -j RETURN"))
    }

    @Test
    fun `tether restore payloads use validated interface without shell quotes`() {
        val plan = plan(tetherUpstreamInterface = "wlan0")
        val activation = TproxyManager.activationRestoreCommand(plan, APP_UID)
        val guard = TproxyManager.guardRestoreCommand(plan, APP_UID)

        assertTrue(activation.contains("-i wlan0 -j RETURN"))
        assertTrue(guard.contains("-i wlan0 -j RETURN"))
        assertFalse(activation.contains("-i '\\''wlan0'\\'' -j RETURN"))
        assertFalse(guard.contains("-i '\\''wlan0'\\'' -j RETURN"))
    }

    @Test
    fun `health verification covers marks UDP and live listeners`() {
        val command = TproxyManager.verifyCommand(plan().runtimeState, APP_UID)

        assertTrue(command.contains("--set-xmark 0xa000001/0xffffffff"))
        assertTrue(command.contains("-p udp -m mark --mark 0xa000001/0xffffffff"))
        assertTrue(command.contains("-d 127.0.0.0/8 -p udp --dport 48321 -j DROP"))
        assertTrue(
            command.contains(
                "iptables -t mangle -C MXOA278b -p udp --dport 53 " +
                    "-j MARK --set-xmark 0xa000001/0xffffffff",
            ),
        )
        assertTrue(command.contains("ss -lnu"))
        assertEquals(1, command.split("ss -lnt").size - 1)
        assertEquals(1, command.split("ss -lnu").size - 1)
        assertEquals(1, command.split("iptables -t mangle -S MXOA278b").size - 1)
        assertTrue(command.contains("ip6tables -t filter -C OUTPUT"))
        assertTrue(command.contains("--reject-with icmp6-no-route"))
        assertFalse(command.contains("ip6tables -t mangle -C OUTPUT"))
        assertFalse(command.contains("ip -6 rule show"))
    }

    private fun plan(
        allowIpv6: Boolean = false,
        tetherUpstreamInterface: String? = null,
        tetherBypassLan: Boolean = true,
    ): TproxyTrafficPlan {
        val state = TproxyManager.createRuntimeState(
            routeTable = 300,
            groups = listOf(Long.MAX_VALUE to "tproxy-in-default", 7L to "app-in-7"),
            ports = listOf(48_321, 48_322),
            allowIpv6 = allowIpv6,
            tetherUpstreamInterface = tetherUpstreamInterface,
            tetherBypassLan = tetherBypassLan,
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
