package com.material.xray.service

internal data class WatchdogSession(
    val generation: Long,
    val pid: Int,
)

internal class WatchdogSessionTracker {
    private var generation = 0L
    private var current: WatchdogSession? = null

    @Synchronized
    fun start(pid: Int): WatchdogSession {
        require(pid > 0)
        generation++
        return WatchdogSession(generation, pid).also { current = it }
    }

    @Synchronized
    fun stop() {
        generation++
        current = null
    }

    @Synchronized
    fun isWatching(pid: Int): Boolean = current?.pid == pid

    @Synchronized
    fun matches(session: WatchdogSession, currentPid: Int?): Boolean = current == session && currentPid == session.pid
}
