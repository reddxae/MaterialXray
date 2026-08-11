package com.material.xray.service

import com.material.xray.core.xray.XraySysStats
import com.material.xray.model.ConnectionState
import com.material.xray.model.XrayRuntimeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class XrayHealthWatchdogConfig(
    val processIntervalMs: Long,
    val memoryCheckIntervalMs: Long,
    val apiProbeIntervalMs: Long,
    val snapshotIntervalMs: Long,
    val tunnelFailureThreshold: Int,
    val apiFailureThreshold: Int,
    val checkFailureLogThreshold: Int,
)

internal interface XrayHealthProbe {
    suspend fun isProcessAlive(pid: Int): Boolean
    suspend fun readCrashReason(): String
    suspend fun readProcessResidentMemoryMb(pid: Int): Long?
    suspend fun readXraySysStats(): XraySysStats?
}

internal class XrayHealthWatchdog(
    private val scope: CoroutineScope,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val healthProbe: XrayHealthProbe,
    private val log: LogBuffer,
    private val config: XrayHealthWatchdogConfig,
    private val passiveMonitoringEnabled: () -> Boolean,
    private val memoryRestartThresholdMiB: () -> Int,
    private val elapsedRealtime: () -> Long,
    private val tunnelAvailable: suspend (ConnectionState.Connected) -> Boolean,
    private val runtimeModeRecoveryReason: suspend () -> String?,
    private val scheduleNetworkSafetyCheck: suspend () -> Unit,
    private val recover: (reason: String, watchedPid: Int, pidToKill: Int?) -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var processJob: Job? = null
    private var apiJob: Job? = null
    private var generation = 0L

    @Volatile private var currentSession: HealthWatchdogSession? = null

    @Synchronized
    fun start(state: ConnectionState.Connected): Boolean {
        val allJobsActive = processJob?.isActive == true &&
            (!passiveMonitoringEnabled() || apiJob?.isActive == true)
        if (currentSession?.pid == state.corePid && allJobsActive) return false

        cancelJobs()
        val session = HealthWatchdogSession(++generation, state.corePid)
        currentSession = session
        startProcessHealth(session)
        if (passiveMonitoringEnabled()) startApiHealth(session)
        return true
    }

    @Synchronized
    fun stop() {
        generation++
        currentSession = null
        cancelJobs()
    }

    private fun cancelJobs() {
        processJob?.cancel()
        processJob = null
        apiJob?.cancel()
        apiJob = null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startProcessHealth(session: HealthWatchdogSession) {
        val healthMonitor = healthMonitor()
        processJob = scope.launch(dispatcher) {
            var keepWatching = true
            var consecutiveCheckFailures = 0
            while (isActive && keepWatching) {
                delay(config.processIntervalMs)
                try {
                    keepWatching = checkProcessHealth(session, healthMonitor)
                    if (keepWatching && consecutiveCheckFailures > 0) {
                        log.append(
                            LogSource.APP,
                            "Local Xray health monitoring recovered after $consecutiveCheckFailures failed check(s)",
                        )
                    }
                    consecutiveCheckFailures = 0
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    consecutiveCheckFailures++
                    if (consecutiveCheckFailures == 1 || consecutiveCheckFailures == config.checkFailureLogThreshold) {
                        log.append(
                            LogSource.APP,
                            "Local Xray health check failed ($consecutiveCheckFailures): " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startApiHealth(session: HealthWatchdogSession) {
        val healthMonitor = healthMonitor()
        apiJob = scope.launch(dispatcher) {
            delay(config.processIntervalMs)
            while (isActive && isCurrent(session)) {
                try {
                    checkApiHealth(session, healthMonitor)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.append(
                        LogSource.APP,
                        "Local Xray API health check failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
                delay(config.apiProbeIntervalMs)
            }
        }
    }

    private suspend fun checkProcessHealth(
        session: HealthWatchdogSession,
        healthMonitor: LocalXrayHealthMonitor,
    ): Boolean {
        val pid = session.pid
        val state = stateCoordinator.state.value as? ConnectionState.Connected ?: return false
        if (!isCurrent(session) || state.corePid != pid) return false

        val reason = runtimeModeRecoveryReason()
        if (!isCurrent(session)) return false
        if (reason != null) return !recover(reason, pid, null)

        if (!healthProbe.isProcessAlive(pid)) {
            if (!isCurrent(session)) return false
            val reason = healthProbe.readCrashReason()
            if (!isCurrent(session)) return false
            return !recover("xray process $pid exited unexpectedly ($reason); reconnecting...", pid, null)
        }
        if (!isCurrent(session)) return false

        if (passiveMonitoringEnabled() && !checkTunnel(session, state, healthMonitor)) return false

        val now = elapsedRealtime()
        if (healthMonitor.shouldCheckMemory(now)) {
            val residentMemoryMb = healthProbe.readProcessResidentMemoryMb(pid)
            if (!isCurrent(session)) return false
            val thresholdMiB = memoryRestartThresholdMiB()
            if (XrayRuntimeSettings.shouldRestartForMemory(residentMemoryMb, thresholdMiB)) {
                return !recover(
                    "xray process $pid exceeded $thresholdMiB MiB RSS ($residentMemoryMb MiB); restarting...",
                    pid,
                    pid,
                )
            }
        }
        scheduleNetworkSafetyCheck()
        return true
    }

    private suspend fun checkTunnel(
        session: HealthWatchdogSession,
        state: ConnectionState.Connected,
        healthMonitor: LocalXrayHealthMonitor,
    ): Boolean {
        val transition = healthMonitor.recordTunnelAvailability(tunnelAvailable(state))
        when {
            transition.recovered -> log.append(
                LogSource.APP,
                "Xray tunnel became available again after ${transition.consecutiveFailures} failed check(s)",
            )
            transition.consecutiveFailures == 1 -> log.append(
                LogSource.APP,
                "Xray tunnel is unavailable; verifying before recovery",
            )
        }
        if (transition.consecutiveFailures < config.tunnelFailureThreshold) return true
        if (!isCurrent(session)) return false
        return !recover(
            "xray process ${state.corePid} is alive but tunnel ${state.tunName} is unavailable; reconnecting...",
            state.corePid,
            null,
        )
    }

    private suspend fun checkApiHealth(session: HealthWatchdogSession, healthMonitor: LocalXrayHealthMonitor) {
        val pid = session.pid
        val now = elapsedRealtime()
        if (!healthMonitor.shouldProbeApi(now)) return

        val sysStats = healthProbe.readXraySysStats()
        if (!isCurrent(session)) return
        val transition = healthMonitor.recordApiResponsiveness(sysStats != null)
        when {
            transition.recovered -> log.append(
                LogSource.APP,
                "Local Xray API recovered after ${transition.consecutiveFailures} failed probe(s)",
            )
            transition.consecutiveFailures == 1 -> log.append(
                LogSource.APP,
                "Local Xray API did not respond; the core process is still alive",
            )
            transition.thresholdReached -> log.append(
                LogSource.APP,
                "Local Xray API is unresponsive after ${transition.consecutiveFailures} probes; " +
                    "automatic restart was suppressed because forwarding may still be healthy",
            )
        }

        if (sysStats != null && healthMonitor.shouldRecordSnapshot(now)) {
            val residentMemoryMb = healthProbe.readProcessResidentMemoryMb(pid)
            log.append(
                LogSource.APP,
                "Xray health: pid=$pid, rss=${residentMemoryMb?.let { "$it MiB" } ?: "unknown"}, " +
                    "goAlloc=${sysStats.alloc.bytesToMiB()} MiB, goSys=${sysStats.sys.bytesToMiB()} MiB, " +
                    "goroutines=${sysStats.numGoroutine}, liveObjects=${sysStats.liveObjects}, " +
                    "uptime=${sysStats.uptimeSeconds}s",
            )
        }
    }

    private fun healthMonitor() = LocalXrayHealthMonitor(
        memoryCheckIntervalMs = config.memoryCheckIntervalMs,
        apiProbeIntervalMs = config.apiProbeIntervalMs,
        snapshotIntervalMs = config.snapshotIntervalMs,
        tunnelFailureThreshold = config.tunnelFailureThreshold,
        apiFailureThreshold = config.apiFailureThreshold,
    )

    private fun isCurrent(session: HealthWatchdogSession): Boolean {
        val currentPid = (stateCoordinator.state.value as? ConnectionState.Connected)?.corePid
        return currentSession == session && currentPid == session.pid
    }
}

private data class HealthWatchdogSession(
    val generation: Long,
    val pid: Int,
)

private const val BYTES_PER_MIB = 1024L * 1024L

private fun Long.bytesToMiB(): Long = this / BYTES_PER_MIB
