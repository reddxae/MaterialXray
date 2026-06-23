package com.material.xray.core.xray

import android.net.LocalSocketAddress
import android.util.Log
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class XrayStatsClient(
    private val socketName: String = XRAY_API_SOCKET_NAME_PREFIX,
    private val timeoutMs: Long = XRAY_API_TIMEOUT_MS,
) {
    suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = queryStats(pattern = "outbound")

    suspend fun queryStats(pattern: String, reset: Boolean = false): Map<String, Long> = withContext(Dispatchers.IO) {
        withBlockingStub { stub ->
            val response = stub.queryStats(
                QueryStatsRequest.newBuilder()
                    .setPattern(pattern)
                    .setReset(reset)
                    .build()
            )
            response.statList.associate { stat -> stat.name to stat.value }
        }.getOrElse { error ->
            Log.w(TAG, "Xray stats query failed", error)
            emptyMap()
        }
    }

    suspend fun getSysStats(): XraySysStats? = withContext(Dispatchers.IO) {
        withBlockingStub { stub ->
            val response = stub.getSysStats(SysStatsRequest.getDefaultInstance())
            XraySysStats(
                numGoroutine = response.numGoroutine,
                numGc = response.numGC,
                alloc = response.alloc,
                totalAlloc = response.totalAlloc,
                sys = response.sys,
                mallocs = response.mallocs,
                frees = response.frees,
                liveObjects = response.liveObjects,
                pauseTotalNs = response.pauseTotalNs,
                uptimeSeconds = response.uptime,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Xray sys stats query failed", error)
            null
        }
    }

    private fun <T> withBlockingStub(block: (StatsServiceGrpc.StatsServiceBlockingStub) -> T): Result<T> {
        var channel: ManagedChannel? = null
        return try {
            channel = buildChannel()
            val stub = StatsServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            Result.success(block(stub))
        } catch (e: StatusRuntimeException) {
            Result.failure(e)
        } catch (e: RuntimeException) {
            Result.failure(e)
        } finally {
            channel?.shutdownNow()
            channel?.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun buildChannel(): ManagedChannel = OkHttpChannelBuilder
        .forTarget(UNUSED_XRAY_API_GRPC_TARGET, InsecureChannelCredentials.create())
        .socketFactory(AndroidLocalSocketFactory(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        .proxyDetector { null }
        .build()
}

private const val UNUSED_XRAY_API_GRPC_TARGET = "dns:///127.0.0.1"
private const val TAG = "XrayStatsClient"

internal data class XraySysStats(
    val numGoroutine: Int,
    val numGc: Int,
    val alloc: Long,
    val totalAlloc: Long,
    val sys: Long,
    val mallocs: Long,
    val frees: Long,
    val liveObjects: Long,
    val pauseTotalNs: Long,
    val uptimeSeconds: Int,
)
