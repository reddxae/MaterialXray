package com.material.xray.core.xray

import android.util.Log
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class XrayStatsClient(
    private val endpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
    private val timeoutMs: Long = XRAY_API_TIMEOUT_MS,
) : AutoCloseable {
    private val channelDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::buildChannel)
    private val channel by channelDelegate
    private val stub by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StatsServiceGrpc.newBlockingStub(channel)
    }

    suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = queryStats(pattern = "outbound")

    suspend fun queryStats(pattern: String, reset: Boolean = false): Map<String, Long> = withContext(Dispatchers.IO) {
        withBlockingStub { stub ->
            val response = stub.queryStats(
                QueryStatsRequest.newBuilder()
                    .setPattern(pattern)
                    .setReset(reset)
                    .build(),
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

    private fun <T> withBlockingStub(block: (StatsServiceGrpc.StatsServiceBlockingStub) -> T): Result<T> = try {
        Result.success(block(stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)))
    } catch (e: StatusRuntimeException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: IllegalStateException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(e)
    }

    override fun close() {
        if (channelDelegate.isInitialized()) channel.shutdownNow()
    }

    private fun buildChannel(): ManagedChannel = buildXrayApiChannel(endpoint)
}

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
