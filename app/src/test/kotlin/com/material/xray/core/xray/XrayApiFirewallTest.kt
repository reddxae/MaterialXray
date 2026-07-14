package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayApiFirewallTest {
    @Test
    fun `apply restricts loopback API to the app UID`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            RootShell.Result(
                exitCode = if (command.startsWith("iptables -w -C OUTPUT")) 1 else 0,
                output = if (command == "iptables -w -S") FIREWALL_CHAINS else "",
                error = "",
            )
        }

        assertTrue(firewall.apply(port = 48_123, appUid = 10_518))

        val configuration = commands.first { "-N mxray_api_10518_a" in it }
        assertTrue(configuration.contains("--dport 48123 -m owner --uid-owner 10518 -j ACCEPT"))
        assertTrue(configuration.contains("--dport 48123 -j REJECT"))
        assertTrue("iptables -w -I OUTPUT 1 -j mxray_api_10518_a" in commands)
    }

    @Test
    fun `apply activates replacement before removing live chain`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            RootShell.Result(
                exitCode = if (command == "iptables -w -C OUTPUT -j mxray_api_10518_b") 1 else 0,
                output = if (command == "iptables -w -S") FIREWALL_CHAINS else "",
                error = "",
            )
        }

        assertTrue(firewall.apply(port = 48_123, appUid = 10_518))

        val activationIndex = commands.indexOf("iptables -w -I OUTPUT 1 -j mxray_api_10518_b")
        val oldChainRemovalIndex = commands.indexOfLast { "-D OUTPUT -j mxray_api_10518_a" in it }
        assertTrue(activationIndex >= 0)
        assertTrue(oldChainRemovalIndex > activationIndex)
    }

    @Test
    fun `apply removes partial replacement after configuration failure`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            RootShell.Result(
                exitCode = if (
                    command.startsWith("iptables -w -C OUTPUT") ||
                    "-N mxray_api_10518_a" in command
                ) {
                    1
                } else {
                    0
                },
                output = if (command == "iptables -w -S") FIREWALL_CHAINS else "",
                error = "failed",
            )
        }

        assertFalse(firewall.apply(port = 48_123, appUid = 10_518))
        assertFalse(commands.any { "-I OUTPUT" in it })
        assertTrue(commands.last().contains("-X mxray_api_10518_a"))
    }

    @Test
    fun `apply leaves live chain in place when replacement activation fails`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            val failed = command == "iptables -w -C OUTPUT -j mxray_api_10518_b" ||
                command == "iptables -w -I OUTPUT 1 -j mxray_api_10518_b"
            RootShell.Result(
                exitCode = if (failed) 1 else 0,
                output = if (command == "iptables -w -S") FIREWALL_CHAINS else "",
                error = if (failed) "failed" else "",
            )
        }

        assertFalse(firewall.apply(port = 48_123, appUid = 10_518))
        assertFalse(commands.any { "-D OUTPUT -j mxray_api_10518_a" in it })
        assertTrue(commands.last().contains("-X mxray_api_10518_b"))
    }

    @Test
    fun `apply makes no changes when existing rules cannot be inspected`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            RootShell.Result(
                exitCode = 1,
                output = "",
                error = "unavailable",
            )
        }

        assertFalse(firewall.apply(port = 48_123, appUid = 10_518))
        assertFalse(commands.any { " -N " in it || " -A " in it || " -I " in it })
    }

    @Test
    fun `apply rejects invalid port and UID without shell access`() = runTest {
        var called = false
        val firewall = XrayApiFirewall {
            called = true
            RootShell.Result(exitCode = 0, output = "", error = "")
        }

        assertFalse(firewall.apply(port = 0, appUid = 10_518))
        assertFalse(firewall.apply(port = 48_123, appUid = 0))
        assertFalse(called)
    }

    @Test
    fun `remove only touches UID-scoped chains`() = runTest {
        val commands = mutableListOf<String>()
        val firewall = XrayApiFirewall { command ->
            commands += command
            RootShell.Result(
                exitCode = 0,
                output = if (command == "iptables -w -S") FIREWALL_CHAINS else "",
                error = "",
            )
        }

        firewall.remove(appUid = 10_518)

        assertEquals(4, commands.size)
        assertTrue(commands[1].contains("mxray_api_10518_a"))
        assertTrue(commands[3].contains("mxray_api_10518_b"))
        assertFalse(commands.any { "-F OUTPUT" in it || "-X OUTPUT" in it })
    }

    private companion object {
        const val FIREWALL_CHAINS = "-N mxray_api_10518_a\n-N mxray_api_10518_b"
    }
}
