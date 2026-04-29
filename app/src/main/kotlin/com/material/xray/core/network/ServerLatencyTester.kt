package com.material.xray.core.network

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerLatencyTester @Inject constructor() {
    suspend fun tcping(address: String, port: Int): Int = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TEST_TIMEOUT_MS) {
            val host = address.trim().trim('[', ']')
            if (host.isBlank() || port !in 1..65535) return@withTimeoutOrNull -1

            measureTcpConnect(host, port)
        } ?: -1
    }

    private suspend fun measureTcpConnect(host: String, port: Int): Int {
        var best = -1
        repeat(TCPING_ATTEMPTS) {
            val latency = socketConnectTime(host, port)
            if (!currentCoroutineContext().isActive) return best
            if (latency >= 0 && (best == -1 || latency < best)) {
                best = latency
            }
        }
        return best
    }

    private fun socketConnectTime(host: String, port: Int): Int {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true

            val startedAt = SystemClock.elapsedRealtimeNanos()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
            elapsedMs.toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 7_000L
        const val TCPING_ATTEMPTS = 2
        const val CONNECT_TIMEOUT_MS = 3_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
