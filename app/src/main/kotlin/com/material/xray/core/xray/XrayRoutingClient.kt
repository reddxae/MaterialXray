package com.material.xray.core.xray

import android.util.Log
import com.material.xray.model.ActiveBalancerSelection
import com.xray.app.router.command.GetBalancerInfoRequest
import com.xray.app.router.command.RoutingServiceGrpc
import com.xray.core.app.observatory.command.GetOutboundStatusRequest
import com.xray.core.app.observatory.command.ObservatoryServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class XrayRoutingClient(
    private val endpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
    private val timeoutMs: Long = XRAY_API_TIMEOUT_MS,
) : AutoCloseable {
    private val channelDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::buildChannel)
    private val channel by channelDelegate
    private val routingStub by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoutingServiceGrpc.newBlockingStub(channel)
    }
    private val observatoryStub by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ObservatoryServiceGrpc.newBlockingStub(channel)
    }

    suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection? = withContext(Dispatchers.IO) {
        withChannel {
            val response = routingStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).getBalancerInfo(
                GetBalancerInfoRequest.newBuilder()
                    .setTag(balancerTag)
                    .build(),
            )
            val outboundTag = response.balancer.override.target.takeIf { it.isNotBlank() }
                ?: response.balancer.principleTarget.tagList.singleOrNull()?.takeIf { it.isNotBlank() }
                ?: return@withChannel null
            val latencyMs = runCatching {
                observatoryStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getOutboundStatus(GetOutboundStatusRequest.getDefaultInstance())
                    .status
                    .statusList
                    .firstOrNull { it.outboundTag == outboundTag && it.alive }
                    ?.delay
            }.getOrNull()
            ActiveBalancerSelection(outboundTag = outboundTag, latencyMs = latencyMs)
        }.getOrElse { error ->
            Log.w(TAG, "Xray balancer query failed", error)
            null
        }
    }

    private fun <T> withChannel(block: () -> T): Result<T> = try {
        Result.success(block())
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

private const val TAG = "XrayRoutingClient"
