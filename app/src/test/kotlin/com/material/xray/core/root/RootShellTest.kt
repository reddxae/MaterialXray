package com.material.xray.core.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootShellTest {
    @Test
    fun `unqualified root commands default to the init namespace`() {
        assertEquals(
            RootShell.NetworkNamespace.INIT,
            RootShell(appProcessId = 123).defaultNetworkNamespace(),
        )
    }

    @Test
    fun `namespace identity can map directly to app init or both`() {
        assertEquals(
            setOf(RootShell.NetworkNamespace.CURRENT),
            detectDirectRootShellNamespaces("net:[41]", "net:[41]", "net:[42]"),
        )
        assertEquals(
            setOf(RootShell.NetworkNamespace.INIT),
            detectDirectRootShellNamespaces("net:[42]", "net:[41]", "net:[42]"),
        )
        assertEquals(
            setOf(RootShell.NetworkNamespace.CURRENT, RootShell.NetworkNamespace.INIT),
            detectDirectRootShellNamespaces("net:[42]", "net:[42]", "net:[42]"),
        )
    }

    @Test
    fun `missing or third-party namespace IDs remain indirect`() {
        assertEquals(
            emptySet<RootShell.NetworkNamespace>(),
            detectDirectRootShellNamespaces("net:[40]", "net:[41]", "net:[42]"),
        )
        assertEquals(
            emptySet<RootShell.NetworkNamespace>(),
            detectDirectRootShellNamespaces(null, "net:[41]", "net:[42]"),
        )
    }

    @Test
    fun `tagged namespace probes preserve missing values`() {
        assertEquals(
            mapOf("shell" to "net:[40]", "app" to "", "init" to "net:[42]"),
            parseTaggedRootValues("shell=net:[40]\napp=\ninit=net:[42]"),
        )
    }

    @Test
    fun `same-namespace commands retain an isolated child shell`() {
        assertEquals(
            "sh -c 'ip rule show'",
            wrapRootCommand(
                command = "ip rule show",
                requestedNamespace = RootShell.NetworkNamespace.INIT,
                directNamespaces = setOf(RootShell.NetworkNamespace.INIT),
                appProcessId = 123,
            ),
        )
    }

    @Test
    fun `cross-namespace commands target init or app process`() {
        assertEquals(
            "nsenter -t 1 -n -- sh -c 'printf '\\''init'\\'''",
            wrapRootCommand(
                command = "printf 'init'",
                requestedNamespace = RootShell.NetworkNamespace.INIT,
                directNamespaces = setOf(RootShell.NetworkNamespace.CURRENT),
                appProcessId = 123,
            ),
        )
        assertEquals(
            "nsenter -t 123 -n -- sh -c 'ip link show'",
            wrapRootCommand(
                command = "ip link show",
                requestedNamespace = RootShell.NetworkNamespace.CURRENT,
                directNamespaces = setOf(RootShell.NetworkNamespace.INIT),
                appProcessId = 123,
            ),
        )
    }
}
