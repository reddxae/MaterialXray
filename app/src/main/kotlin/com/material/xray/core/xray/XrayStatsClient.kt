package com.material.xray.core.xray

import android.net.LocalSocketAddress
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.android.UdsChannelBuilder
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
        }.getOrDefault(emptyMap())
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
        }.getOrNull()
    }

    private fun <T> withBlockingStub(block: (StatsServiceGrpc.StatsServiceBlockingStub) -> T): Result<T> {
        val channel = buildChannel()
        return try {
            val stub = StatsServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            Result.success(block(stub))
        } catch (e: StatusRuntimeException) {
            Result.failure(e)
        } catch (e: RuntimeException) {
            Result.failure(e)
        } finally {
            channel.shutdownNow()
            channel.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun buildChannel(): ManagedChannel = UdsChannelBuilder
        .forPath(socketName, LocalSocketAddress.Namespace.ABSTRACT)
        .build()
}

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
