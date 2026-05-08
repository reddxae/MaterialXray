package com.material.xray.core.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.buildDns
import com.material.xray.core.xray.buildProxyOutbound
import com.material.xray.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class LatencyProbeResult(
    val latencyMs: Int,
    val usedTcpFallback: Boolean = false,
)

@Singleton
class ServerLatencyTester @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val baseClient: OkHttpClient,
) {
    private val json = Json { prettyPrint = true }
    private val xrayBinary = XrayBinary(context)
    private val serverAddressResolver = ServerAddressResolver()

    suspend fun measure(
        server: ServerConfig,
        probeUrl: String,
        dnsServers: String,
        allowIpv6: Boolean,
    ): LatencyProbeResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TEST_TIMEOUT_MS) {
            val e2eLatency = measureHttpProbeThroughXray(
                server = resolveServerForProbe(server, allowIpv6)
                    ?: return@withTimeoutOrNull LatencyProbeResult(latencyMs = -1),
                probeUrl = probeUrl.trim().ifBlank { DEFAULT_PROBE_URL },
                dnsServers = dnsServers.ifBlank { DEFAULT_DNS_SERVERS },
                allowIpv6 = allowIpv6,
            )
            if (e2eLatency >= 0) {
                LatencyProbeResult(latencyMs = e2eLatency)
            } else {
                Log.d(TAG, "E2E latency probe failed for ${server.name}; falling back to TCP connect")
                val tcpLatency = measureTcpConnect(server.address, server.port)
                LatencyProbeResult(
                    latencyMs = tcpLatency,
                    usedTcpFallback = tcpLatency >= 0,
                )
            }
        } ?: LatencyProbeResult(latencyMs = -1)
    }

    private suspend fun resolveServerForProbe(server: ServerConfig, allowIpv6: Boolean): ServerConfig? {
        if (server.rawConfigJson.isNotBlank()) return server

        val resolved = serverAddressResolver.resolve(server, allowIpv6)
        if (resolved.attempted && resolved.selectedAddress == null) {
            Log.d(TAG, "Could not resolve ${server.address} before latency probe")
            return null
        }
        return resolved.server
    }

    private suspend fun measureHttpProbeThroughXray(
        server: ServerConfig,
        probeUrl: String,
        dnsServers: String,
        allowIpv6: Boolean,
    ): Int {
        if (!xrayBinary.ensureAndroidBinaryAvailable()) return -1

        val binDir = context.filesDir.resolve("bin").also { it.mkdirs() }
        val probeDir = context.cacheDir.resolve("latency").also { it.mkdirs() }
        val probeId = UUID.randomUUID().toString()
        val proxyPort = reserveLocalPort()
        val configFile = probeDir.resolve("xray-latency-$probeId.json")
        val logFile = probeDir.resolve("xray-latency-$probeId.log")
        val configJson = runCatching { buildLatencyConfig(server, proxyPort, dnsServers, allowIpv6) }.getOrElse { return -1 }
        configFile.writeText(configJson)

        val process = runCatching {
            ProcessBuilder(requireNotNull(xrayBinary.androidBinaryPath), "run", "-c", configFile.absolutePath)
                .directory(binDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .apply {
                    environment()["xray.location.asset"] = binDir.absolutePath
                    environment()["XRAY_LOCATION_ASSET"] = binDir.absolutePath
                }
                .start()
        }.getOrElse {
            configFile.delete()
            logFile.delete()
            return -1
        }

        return try {
            if (!waitForLocalPort(proxyPort, process)) return -1
            requestProbeThroughProxy(proxyPort, probeUrl)
        } finally {
            withContext(NonCancellable) {
                stopProcess(process)
                if (process.exitValueOrNull() != 0 && logFile.isFile) {
                    logFile.readText()
                        .lines()
                        .lastOrNull { it.isNotBlank() }
                        ?.let { Log.d(TAG, "Latency probe xray exited: $it") }
                }
                configFile.delete()
                logFile.delete()
            }
        }
    }

    private fun buildLatencyConfig(
        server: ServerConfig,
        proxyPort: Int,
        dnsServers: String,
        allowIpv6: Boolean,
    ): String {
        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("access", "none")
                put("loglevel", "error")
            })
            put("dns", buildDns(dnsServers, allowIpv6 = allowIpv6))
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", "latency-socks")
                    put("listen", LOCAL_PROXY_HOST)
                    put("port", proxyPort)
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("udp", false)
                        put("timeout", LOCAL_PROXY_TIMEOUT_SECONDS)
                    })
                })
            })
            put("outbounds", buildJsonArray {
                add(buildProxyOutbound(server, fwmark = 0, physicalInterface = null, tag = "proxy", allowIpv6 = allowIpv6))
            })
            put("routing", buildJsonObject {
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add("latency-socks") })
                        put("outboundTag", "proxy")
                    })
                })
            })
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    private suspend fun waitForLocalPort(port: Int, process: Process): Boolean {
        var elapsedMs = 0L
        while (elapsedMs <= LOCAL_PROXY_START_TIMEOUT_MS) {
            if (!process.isAlive) return false
            if (canConnectLocalPort(port)) return true
            delay(LOCAL_PROXY_POLL_INTERVAL_MS)
            elapsedMs += LOCAL_PROXY_POLL_INTERVAL_MS
        }
        return false
    }

    private fun canConnectLocalPort(port: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(LOCAL_PROXY_HOST, port), LOCAL_CONNECT_TIMEOUT_MS)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun requestProbeThroughProxy(proxyPort: Int, probeUrl: String): Int {
        val client = baseClient.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCAL_PROXY_HOST, proxyPort)))
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return runCatching {
            val request = Request.Builder()
                .url(probeUrl)
                .header("Cache-Control", "no-cache")
                .build()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            client.newCall(request).execute().use { response ->
                if (response.code !in HTTP_SUCCESS_CODES) return -1
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
                elapsedMs.toInt().coerceAtLeast(1)
            }
        }.onFailure { error ->
            Log.d(TAG, "E2E latency request failed: ${error.message}")
        }.getOrDefault(-1)
    }

    private suspend fun measureTcpConnect(address: String, port: Int): Int {
        val host = address.trim().trim('[', ']')
        if (host.isBlank() || port !in 1..65535) return -1

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
            socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
            elapsedMs.toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun reserveLocalPort(): Int =
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }

    private suspend fun stopProcess(process: Process) {
        if (process.isAlive) {
            process.destroy()
            var elapsedMs = 0L
            while (process.isAlive && elapsedMs <= PROCESS_STOP_TIMEOUT_MS) {
                delay(PROCESS_STOP_POLL_INTERVAL_MS)
                elapsedMs += PROCESS_STOP_POLL_INTERVAL_MS
            }
            if (process.isAlive) process.destroyForcibly()
        }
        process.waitForExit()
    }

    private fun Process.exitValueOrNull(): Int? =
        runCatching { exitValue() }.getOrNull()

    private fun Process.waitForExit() {
        runCatching {
            waitFor(PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val TAG = "MXray.Latency"
        const val DEFAULT_PROBE_URL = "https://gstatic.com/generate_204"
        const val DEFAULT_DNS_SERVERS = "77.88.8.8,77.88.8.1"
        val HTTP_SUCCESS_CODES = 200..399
        const val TEST_TIMEOUT_MS = 12_000L
        const val HTTP_TIMEOUT_MS = 8_000L
        const val TCPING_ATTEMPTS = 2
        const val TCP_CONNECT_TIMEOUT_MS = 3_000
        const val LOCAL_PROXY_HOST = "127.0.0.1"
        const val LOCAL_PROXY_TIMEOUT_SECONDS = 8
        const val LOCAL_PROXY_START_TIMEOUT_MS = 1_500L
        const val LOCAL_PROXY_POLL_INTERVAL_MS = 50L
        const val LOCAL_CONNECT_TIMEOUT_MS = 100
        const val PROCESS_STOP_TIMEOUT_MS = 500L
        const val PROCESS_WAIT_TIMEOUT_MS = 500L
        const val PROCESS_STOP_POLL_INTERVAL_MS = 50L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
