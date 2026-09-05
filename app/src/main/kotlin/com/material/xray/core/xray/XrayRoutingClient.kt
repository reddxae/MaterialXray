package com.material.xray.core.xray

import android.util.Log
import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.BalancerOutbound
import com.xray.app.observatory.OutboundStatus
import com.xray.app.router.command.GetBalancerInfoRequest
import com.xray.app.router.command.GetBalancerInfoResponse
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
            val tags = response.selectedOutboundTags()
            if (tags.isEmpty()) return@withChannel ActiveBalancerSelection()
            val statuses = runCatching {
                observatoryStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getOutboundStatus(GetOutboundStatusRequest.getDefaultInstance())
                    .status
                    .statusList
                    .associateBy { it.outboundTag }
            }.getOrDefault(emptyMap())
            ActiveBalancerSelection(
                outbounds = tags.map { tag ->
                    BalancerOutbound(outboundTag = tag, latencyMs = statuses[tag]?.balancerLatencyMs())
                },
            )
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

/** Overrides select one server even when the underlying strategy reports a larger pool. */
internal fun GetBalancerInfoResponse.selectedOutboundTags(): List<String> = (
    balancer.override.target.takeIf { it.isNotBlank() }?.let(::listOf)
        ?: balancer.principleTarget.tagList
    )
    .filter { it.isNotBlank() }
    .distinct()
    .sorted()

internal fun OutboundStatus.balancerLatencyMs(): Long? {
    if (!alive) return null
    if (hasHealthPing()) {
        // Burst observatory stores its average in nanoseconds; regular observatory uses ms.
        return healthPing.average.takeIf { healthPing.all > healthPing.fail && it >= 0 }
            ?.let(TimeUnit.NANOSECONDS::toMillis)
    }
    return delay.takeIf { it >= 0 }
}
