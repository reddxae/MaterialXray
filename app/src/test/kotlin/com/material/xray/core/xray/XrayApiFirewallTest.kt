package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayApiFirewallTest {
    @Test
    fun `apply installs a UID-restricted atomic replacement`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            successfulCommand()
        }

        assertTrue(firewall.apply(port = 48_123, appUid = 10_518))

        val command = commands.single()
        assertTrue(command.contains("refresh_ruleset() { ruleset=\$(iptables -w -S) || return 1; }"))
        assertTrue(command.contains("refresh_ruleset || exit 1"))
        assertTrue(command.contains("--dport 48123 -m owner --uid-owner 10518 -j ACCEPT"))
        assertTrue(command.contains("--dport 48123 -j REJECT"))
        assertTrue(command.contains("iptables -w -I OUTPUT 1 -j \"\$replacement\""))
    }

    @Test
    fun `apply activates replacement before removing live chain`() = runTest {
        var command = ""
        val firewall = XrayApiFirewall { value ->
            command = value
            successfulCommand()
        }

        assertTrue(firewall.apply(port = 48_123, appUid = 10_518))

        val activationIndex = command.indexOf("-I OUTPUT 1 -j \"\$replacement\"")
        val oldChainRemovalIndex = command.lastIndexOf("remove_chain \"\$active\"")
        assertTrue(activationIndex >= 0)
        assertTrue(oldChainRemovalIndex > activationIndex)
    }

    @Test
    fun `apply command aborts before mutation when inspection fails`() = runTest {
        var command = ""
        val firewall = XrayApiFirewall { value ->
            command = value
            RootShell.Result(exitCode = 1, output = "", error = "unavailable")
        }

        assertFalse(firewall.apply(port = 48_123, appUid = 10_518))

        assertTrue(command.indexOf("refresh_ruleset || exit 1") < command.indexOf("iptables -w -N"))
    }

    @Test
    fun `apply command cleans partial replacement on configuration or activation failure`() = runTest {
        var command = ""
        val firewall = XrayApiFirewall { value ->
            command = value
            successfulCommand()
        }

        firewall.apply(port = 48_123, appUid = 10_518)

        val cleanup = "remove_chain \"\$replacement\"; exit 1; fi"
        assertTrue(command.contains(cleanup))
        assertTrue(command.indexOf(cleanup) != command.lastIndexOf(cleanup))
    }

    @Test
    fun `apply rejects invalid port and UID without shell access`() = runTest {
        var called = false
        val firewall = XrayApiFirewall {
            called = true
            successfulCommand()
        }

        assertFalse(firewall.apply(port = 0, appUid = 10_518))
        assertFalse(firewall.apply(port = 48_123, appUid = 0))
        assertFalse(called)
    }

    @Test
    fun `remove handles both UID-scoped chains in one transaction`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            successfulCommand()
        }

        assertTrue(firewall.remove(appUid = 10_518))

        val command = commands.single()
        assertTrue(command.contains("remove_chain mxray_api_10518_a"))
        assertTrue(command.contains("remove_chain mxray_api_10518_b"))
        assertTrue(command.contains("remove_chain mxray_api_10518_a || status=1"))
        assertTrue(command.contains("remove_chain mxray_api_10518_b || status=1"))
        assertFalse(command.contains("-F OUTPUT") || command.contains("-X OUTPUT"))
    }

    private fun successfulCommand() = RootShell.Result(exitCode = 0, output = "", error = "")
}
