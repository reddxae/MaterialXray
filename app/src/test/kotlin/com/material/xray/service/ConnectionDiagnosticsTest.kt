package com.material.xray.service

import com.material.xray.core.root.RootShell
import com.material.xray.core.root.RootShell.NetworkNamespace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosticsTest {

    @Test
    fun `logNamespaceDiagnostics records current and init namespace probes`() = runTest {
        val runner = FakeDiagnosticCommandRunner(defaultNamespace = NetworkNamespace.INIT)
        val log = LogBuffer()

        ConnectionDiagnostics(runner, log).logNamespaceDiagnostics(
            stage = "tun-failure",
            tunName = "xray0",
            xrayPid = 42,
        )

        assertEquals(
            listOf(
                "current-netns" to NetworkNamespace.CURRENT,
                "init-netns" to NetworkNamespace.INIT,
                "current-link" to NetworkNamespace.CURRENT,
                "init-link" to NetworkNamespace.INIT,
                "xray-proc" to NetworkNamespace.CURRENT,
                "xray-link" to NetworkNamespace.CURRENT,
            ),
            runner.calls.map { it.label to it.namespace },
        )
        assertTrue(log.messages().any { it.contains("tun-failure/current-netns: output for current-netns") })
    }

    @Test
    fun `logNamespaceDiagnostics records stderr and exit code for failed commands`() = runTest {
        val runner = FakeDiagnosticCommandRunner(
            defaultNamespace = NetworkNamespace.CURRENT,
            resultForLabel = { label ->
                if (label.endsWith("current-link")) {
                    RootShell.Result(exitCode = 7, output = "", error = "ip failed")
                } else {
                    RootShell.Result(exitCode = 0, output = "ok", error = "")
                }
            },
        )
        val log = LogBuffer()

        ConnectionDiagnostics(runner, log).logNamespaceDiagnostics(
            stage = "app-tun-failure",
            tunName = "xray0",
        )

        assertEquals(
            listOf(
                "current-netns" to NetworkNamespace.CURRENT,
                "current-link" to NetworkNamespace.CURRENT,
            ),
            runner.calls.map { it.label to it.namespace },
        )
        assertTrue(log.messages().contains("app-tun-failure/current-link stderr: ip failed"))
        assertTrue(log.messages().contains("app-tun-failure/current-link: exit=7"))
    }

    private class FakeDiagnosticCommandRunner(
        private val defaultNamespace: NetworkNamespace,
        private val resultForLabel: (String) -> RootShell.Result = { label ->
            RootShell.Result(exitCode = 0, output = "output for $label", error = "")
        },
    ) : DiagnosticCommandRunner {
        val calls = mutableListOf<Call>()

        override fun defaultNetworkNamespace(): NetworkNamespace = defaultNamespace

        override suspend fun execute(command: String, namespace: NetworkNamespace): RootShell.Result {
            val label = when {
                command.contains("nsenter -t 42") -> "xray-link"
                command.contains("cmdline") -> "xray-proc"
                command.contains("ip link show xray0") && namespace == NetworkNamespace.INIT -> "init-link"
                command.contains("ip link show xray0") -> "current-link"
                command.contains("readlink /proc/") && namespace == NetworkNamespace.INIT -> "init-netns"
                command.contains("readlink /proc/") -> "current-netns"
                else -> error("Unexpected diagnostic command: $command")
            }
            calls += Call(label, namespace)
            return resultForLabel(label)
        }
    }

    private data class Call(
        val label: String,
        val namespace: NetworkNamespace,
    )

    private companion object {
        fun LogBuffer.messages(): List<String> = entries.value.map { it.message }
    }
}
