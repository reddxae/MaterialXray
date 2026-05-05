package com.material.xray.service

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XrayProcessSupervisorTest {

    @Test
    fun `start quotes paths and returns launched pid`() = runTest {
        val commands = FakeRootCommandRunner(
            resultForCommand = { command ->
                assertTrue(command.contains("cd '/tmp/xray bin'"))
                assertTrue(command.contains("'/tmp/xray bin/xray' run -c '/tmp/config dir/config.json'"))
                assertTrue(command.contains("> '/tmp/runtime dir/xray.log' 2>&1 & printf '%s' ${'$'}!"))
                RootShell.Result(exitCode = 0, output = "1234", error = "")
            }
        )
        val supervisor = supervisor(commandRunner = commands)

        val pid = supervisor.start("/tmp/xray bin")

        assertEquals(1234, pid)
    }

    @Test
    fun `process liveness and kill reject invalid pids without shelling out`() = runTest {
        val commands = FakeRootCommandRunner()
        val supervisor = supervisor(commandRunner = commands)

        assertFalse(supervisor.isAlive(0))
        assertFalse(supervisor.kill(-1))
        assertTrue(commands.commands.isEmpty())
    }

    @Test
    fun `readResidentMemoryMb rounds VmRSS kilobytes up to megabytes`() = runTest {
        val supervisor = supervisor(
            commandRunner = FakeRootCommandRunner(
                resultForCommand = { command ->
                    if (command.contains("/proc/42/status")) {
                        RootShell.Result(exitCode = 0, output = "2049", error = "")
                    } else {
                        RootShell.Result(exitCode = 1, output = "", error = "")
                    }
                },
            )
        )

        assertEquals(3L, supervisor.readResidentMemoryMb(42))
    }

    @Test
    fun `readResidentMemoryMb falls back to statm resident pages`() = runTest {
        val supervisor = supervisor(
            commandRunner = FakeRootCommandRunner(
                resultForCommand = { command ->
                    if (command.contains("/proc/42/status")) {
                        RootShell.Result(exitCode = 1, output = "", error = "")
                    } else {
                        RootShell.Result(exitCode = 0, output = "512", error = "")
                    }
                },
            )
        )

        assertEquals(2L, supervisor.readResidentMemoryMb(42))
    }

    @Test
    fun `ensureNativeRuntimeExemptions requests battery and network allowlists`() = runTest {
        val environment = FakeRuntimeEnvironment(ignoringBatteryOptimizations = false)
        val commands = FakeRootCommandRunner()
        val log = LogBuffer()

        supervisor(environment = environment, commandRunner = commands, log = log)
            .ensureNativeRuntimeExemptions()

        assertEquals(
            listOf(
                "cmd deviceidle whitelist +'com.material.xray'",
                "cmd netpolicy add restrict-background-whitelist 12345",
            ),
            commands.commands,
        )
        assertTrue(log.entries.value.any { it.message.contains("Requested device idle whitelist") })
        assertTrue(log.entries.value.any { it.message.contains("background-data allowlist") })
    }

    private fun supervisor(
        environment: XrayRuntimeEnvironment = FakeRuntimeEnvironment(),
        commandRunner: FakeRootCommandRunner = FakeRootCommandRunner(),
        log: LogBuffer = LogBuffer(),
    ) = XrayProcessSupervisor(
        environment = environment,
        commandRunner = commandRunner,
        xrayBinary = FakeXrayProcessBinary(),
        log = log,
    )

    private class FakeRootCommandRunner(
        private val resultForCommand: (String) -> RootShell.Result = {
            RootShell.Result(exitCode = 0, output = "", error = "")
        },
    ) : RootCommandRunner {
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): RootShell.Result {
            commands += command
            return resultForCommand(command)
        }
    }

    private class FakeXrayProcessBinary : XrayProcessBinary {
        override val binaryPath: String = "/tmp/xray bin/xray"

        override fun configPath(): String = "/tmp/config dir/config.json"
    }

    private class FakeRuntimeEnvironment(
        override val filesDir: File = File("/tmp/runtime dir"),
        override val packageName: String = "com.material.xray",
        override val packageUid: Int = 12345,
        private val ignoringBatteryOptimizations: Boolean = true,
        private val lowPowerStandbyExempt: Boolean = false,
    ) : XrayRuntimeEnvironment {
        override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

        override fun isExemptFromLowPowerStandby(): Boolean = lowPowerStandbyExempt
    }
}
