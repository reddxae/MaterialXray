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
                assertTrue(command.contains("env 'xray.location.asset=/tmp/xray bin' 'XRAY_LOCATION_ASSET=/tmp/xray bin'"))
                assertTrue(command.contains("sh -c 'exec \"\$@\"' xray"))
                assertTrue(command.contains("config='/tmp/config dir/config.json'"))
                assertTrue(command.contains("'/tmp/xray bin/xray' run -c \"\$config\""))
                assertTrue(command.contains("> '/tmp/runtime dir/xray.log' 2>&1 & launcher=\$!"))
                assertTrue(command.contains("pidof xray"))
                assertTrue(command.contains("printf '%s' \"\${found:-\$launcher}\""))
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

    @Test
    fun `user process stop waits for graceful exit`() = runTest {
        val launcher = FakeUserXrayProcessLauncher(
            alive = { pid, killedSignals -> pid == 42 && 15 !in killedSignals },
        )
        val supervisor = userSupervisor(processLauncher = launcher)
        supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

        supervisor.stop()

        assertEquals(listOf(15), launcher.killedSignals)
    }

    @Test
    fun `user process stop escalates to kill when process remains alive`() = runTest {
        val launcher = FakeUserXrayProcessLauncher(
            alive = { pid, killedSignals -> pid == 42 && 9 !in killedSignals },
        )
        val supervisor = userSupervisor(processLauncher = launcher)
        supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

        supervisor.stop()

        assertEquals(listOf(15, 9), launcher.killedSignals)
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

    private fun userSupervisor(
        environment: XrayRuntimeEnvironment = FakeRuntimeEnvironment(),
        processLauncher: UserXrayProcessLauncher = FakeUserXrayProcessLauncher(),
    ) = UserXrayProcessSupervisor(
        environment = environment,
        xrayBinary = FakeXrayProcessBinary(),
        processLauncher = processLauncher,
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
        override val rootBinaryPath: String = "/tmp/xray bin/xray"
        override val androidBinaryPath: String = "/tmp/android lib/libxray.so"

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

    private class FakeUserXrayProcessLauncher(
        private val alive: (pid: Int, killedSignals: List<Int>) -> Boolean = { pid, _ -> pid == 42 },
    ) : UserXrayProcessLauncher {
        val killedSignals = mutableListOf<Int>()

        override fun start(
            binaryPath: String,
            configPath: String,
            workingDir: String,
            logPath: String,
            tunFd: Int,
            environment: Map<String, String>,
        ): Int = 42

        override fun isAlive(pid: Int): Boolean = alive(pid, killedSignals)

        override fun kill(pid: Int, signal: Int): Boolean {
            killedSignals += signal
            return true
        }
    }
}
