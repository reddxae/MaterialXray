package com.material.xray.service

import com.material.xray.core.xray.XraySysStats
import com.material.xray.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XrayHealthWatchdogTest {
    @Test
    fun `dead process schedules one recovery and stops its process loop`() = runTest {
        val stateCoordinator = ConnectionStateCoordinator()
        val probe = FakeHealthProbe(processAlive = false)
        val recoveries = mutableListOf<String>()
        val watchdog = XrayHealthWatchdog(
            scope = backgroundScope,
            stateCoordinator = stateCoordinator,
            healthProbe = probe,
            log = LogBuffer(),
            config = config(),
            passiveMonitoringEnabled = { false },
            memoryRestartThresholdMiB = { 1_000 },
            elapsedRealtime = { testScheduler.currentTime },
            tunnelAvailable = { true },
            runtimeModeRecoveryReason = { null },
            scheduleNetworkSafetyCheck = {},
            recover = { reason, _, _ ->
                recoveries += reason
                true
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        stateCoordinator.markConnected(connectedState())

        watchdog.start(connectedState())
        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(1, probe.aliveChecks)
        assertEquals(1, recoveries.size)
    }

    @Test
    fun `unresponsive API never restarts a live forwarding process`() = runTest {
        val stateCoordinator = ConnectionStateCoordinator()
        val probe = FakeHealthProbe(processAlive = true, sysStats = null)
        var recoveryCount = 0
        val watchdog = XrayHealthWatchdog(
            scope = backgroundScope,
            stateCoordinator = stateCoordinator,
            healthProbe = probe,
            log = LogBuffer(),
            config = config(),
            passiveMonitoringEnabled = { true },
            memoryRestartThresholdMiB = { 1_000 },
            elapsedRealtime = { testScheduler.currentTime },
            tunnelAvailable = { true },
            runtimeModeRecoveryReason = { null },
            scheduleNetworkSafetyCheck = {},
            recover = { _, _, _ ->
                recoveryCount++
                true
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        stateCoordinator.markConnected(connectedState())

        watchdog.start(connectedState())
        advanceTimeBy(350)
        runCurrent()

        assertEquals(0, recoveryCount)
        assertEquals(3, probe.sysStatsChecks)
    }

    @Test
    fun `restarted watchdog rejects stale result when pid is reused`() = runTest {
        val stateCoordinator = ConnectionStateCoordinator()
        val firstProbeStarted = CompletableDeferred<Unit>()
        val releaseFirstProbe = CompletableDeferred<Unit>()
        var probeCount = 0
        var recoveryCount = 0
        val probe = object : XrayHealthProbe {
            override suspend fun isProcessAlive(pid: Int): Boolean {
                probeCount++
                return if (probeCount == 1) {
                    withContext(NonCancellable) {
                        firstProbeStarted.complete(Unit)
                        releaseFirstProbe.await()
                        false
                    }
                } else {
                    true
                }
            }

            override suspend fun readCrashReason(): String = "stale crash"
            override suspend fun readProcessResidentMemoryMb(pid: Int): Long = 1
            override suspend fun readXraySysStats(): XraySysStats? = null
        }
        val watchdog = XrayHealthWatchdog(
            scope = backgroundScope,
            stateCoordinator = stateCoordinator,
            healthProbe = probe,
            log = LogBuffer(),
            config = config(),
            passiveMonitoringEnabled = { false },
            memoryRestartThresholdMiB = { 1_000 },
            elapsedRealtime = { testScheduler.currentTime },
            tunnelAvailable = { true },
            runtimeModeRecoveryReason = { null },
            scheduleNetworkSafetyCheck = {},
            recover = { _, _, _ ->
                recoveryCount++
                true
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val state = connectedState()
        stateCoordinator.markConnected(state)

        watchdog.start(state)
        advanceTimeBy(100)
        runCurrent()
        firstProbeStarted.await()
        watchdog.stop()
        watchdog.start(state)
        releaseFirstProbe.complete(Unit)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(0, recoveryCount)
        assertEquals(2, probeCount)
    }

    private class FakeHealthProbe(
        private val processAlive: Boolean,
        private val sysStats: XraySysStats? = null,
    ) : XrayHealthProbe {
        var aliveChecks = 0
        var sysStatsChecks = 0

        override suspend fun isProcessAlive(pid: Int): Boolean {
            aliveChecks++
            return processAlive
        }

        override suspend fun readCrashReason(): String = "crashed"
        override suspend fun readProcessResidentMemoryMb(pid: Int): Long = 1
        override suspend fun readXraySysStats(): XraySysStats? {
            sysStatsChecks++
            return sysStats
        }
    }

    private companion object {
        fun config() = XrayHealthWatchdogConfig(
            processIntervalMs = 100,
            apiProbeIntervalMs = 100,
            snapshotIntervalMs = 500,
            tunnelFailureThreshold = 2,
            apiFailureThreshold = 3,
            checkFailureLogThreshold = 3,
        )

        fun connectedState() = ConnectionState.Connected(
            serverName = "Test",
            corePid = 42,
            tunName = "xray0",
            physicalInterface = "wlan0",
        )
    }
}
