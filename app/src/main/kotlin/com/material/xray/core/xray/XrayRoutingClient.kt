package com.material.xray.core.xray

import android.net.LocalSocketAddress
import android.util.Log
import com.material.xray.model.ActiveBalancerSelection
import com.xray.app.router.command.GetBalancerInfoRequest
import com.xray.app.router.command.RoutingServiceGrpc
import com.xray.core.app.observatory.command.GetOutboundStatusRequest
import com.xray.core.app.observatory.command.ObservatoryServiceGrpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class XrayRoutingClient(
    private val socketName: String = XRAY_API_SOCKET_NAME_PREFIX,
    private val timeoutMs: Long = XRAY_API_TIMEOUT_MS,
) {
    suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection? = withContext(Dispatchers.IO) {
        withChannel { channel ->
            val routingStub = RoutingServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            val response = routingStub.getBalancerInfo(
                GetBalancerInfoRequest.newBuilder()
                    .setTag(balancerTag)
                    .build(),
            )
            val outboundTag = response.balancer.override.target.takeIf { it.isNotBlank() }
                ?: response.balancer.principleTarget.tagList.singleOrNull()?.takeIf { it.isNotBlank() }
                ?: return@withChannel null
            val latencyMs = runCatching {
                val observatoryStub = ObservatoryServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                observatoryStub.getOutboundStatus(GetOutboundStatusRequest.getDefaultInstance())
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

    private fun <T> withChannel(block: (ManagedChannel) -> T): Result<T> {
        var channel: ManagedChannel? = null
        return try {
            channel = buildChannel()
            Result.success(block(channel))
        } catch (e: StatusRuntimeException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: SecurityException) {
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
private const val TAG = "XrayRoutingClient"
