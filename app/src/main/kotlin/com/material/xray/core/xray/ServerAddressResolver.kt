package com.material.xray.core.xray

import android.net.DnsResolver
import android.os.CancellationSignal
import android.os.Build
import androidx.annotation.RequiresApi
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.random.Random

class ServerAddressResolver {
    data class Result(
        val server: ServerConfig,
        val attempted: Boolean,
        val selectedAddress: String?,
        val candidates: List<String>,
    )

    private val directExecutor = Executor { it.run() }

    suspend fun resolve(server: ServerConfig, allowIpv6: Boolean = false): Result = withContext(Dispatchers.IO) {
        val host = server.address.trim()
        if (host.isEmpty() || isNumericAddress(host)) {
            if (!allowIpv6 && isIpv6Address(host)) {
                return@withContext Result(server, attempted = true, selectedAddress = null, candidates = emptyList())
            }
            return@withContext Result(server, attempted = false, selectedAddress = null, candidates = emptyList())
        }

        val candidates = coroutineScope {
            val androidDns = async {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveWithAndroidDns(host) } ?: emptyList()
                } else {
                    emptyList()
                }
            }
            val okHttpDns = async { resolveWithOkHttpDns(host) }
            (androidDns.await() + okHttpDns.await()).distinct()
        }.filter { allowIpv6 || !isIpv6Address(it) }
        if (candidates.isEmpty()) {
            return@withContext Result(server, attempted = true, selectedAddress = null, candidates = emptyList())
        }

        val selectedAddress = candidates.random(Random(System.nanoTime()))
        Result(
            server = server.withResolvedAddress(selectedAddress, originalHost = host),
            attempted = true,
            selectedAddress = selectedAddress,
            candidates = candidates,
        )
    }

    private fun ServerConfig.withResolvedAddress(address: String, originalHost: String): ServerConfig {
        val resolvedSecurity = if (security.sni.isEmpty() && security.type in setOf("tls", "reality")) {
            security.copy(sni = originalHost)
        } else {
            security
        }

        val resolvedTransport = if (transport.host.isEmpty() && transport.type in setOf("ws", "xhttp", "httpupgrade")) {
            transport.copy(host = originalHost)
        } else {
            transport
        }

        return copy(address = address, security = resolvedSecurity, transport = resolvedTransport)
    }

    private fun isNumericAddress(host: String): Boolean {
        val value = host.trim('[', ']')
        if (value.contains(':')) {
            return runCatching { InetAddress.getByName(value) }.isSuccess
        }
        return ipv4Pattern.matches(value) && value.split('.').all { it.toIntOrNull() in 0..255 }
    }

    private fun isIpv6Address(host: String): Boolean {
        val value = host.trim('[', ']')
        return value.contains(':') && runCatching { InetAddress.getByName(value) }.isSuccess
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolveWithAndroidDns(host: String): List<String> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }

        DnsResolver.getInstance().query(
            null,
            host,
            DnsResolver.FLAG_NO_CACHE_LOOKUP,
            directExecutor,
            cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (continuation.isActive) {
                        continuation.resume(answer.mapNotNull { it.hostAddress })
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
            },
        )
    }

    private fun resolveWithOkHttpDns(host: String): List<String> = runCatching {
        Dns.SYSTEM.lookup(host).mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3000L
        val ipv4Pattern = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }
}
