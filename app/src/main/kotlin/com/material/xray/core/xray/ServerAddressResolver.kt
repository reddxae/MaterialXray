package com.material.xray.core.xray

import android.content.Context
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import com.material.xray.model.ServerConfig
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Dns

class ServerAddressResolver(
    private val context: Context? = null,
    private val hostLookup: (suspend (String) -> List<String>)? = null,
) {
    data class Result(
        val server: ServerConfig,
        val attempted: Boolean,
        val selectedAddress: String?,
        val candidates: List<String>,
        val unresolvedHosts: List<String> = emptyList(),
    )

    private val directExecutor = Executor { it.run() }

    suspend fun resolve(server: ServerConfig, allowIpv6: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (server.rawConfigJson.isNotBlank()) {
            return@withContext resolveRawConfig(server, allowIpv6)
        }

        val host = server.address.trim()
        if (host.isEmpty() || isNumericAddress(host)) {
            if (!allowIpv6 && isIpv6Address(host)) {
                return@withContext Result(server, attempted = true, selectedAddress = null, candidates = emptyList())
            }
            return@withContext Result(server, attempted = false, selectedAddress = null, candidates = emptyList())
        }

        val candidates = resolveHost(host, allowIpv6)
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

    private suspend fun resolveRawConfig(server: ServerConfig, allowIpv6: Boolean): Result {
        val endpoints = rawProxyEndpoints(server.rawConfigJson)
        if (!allowIpv6 && endpoints.ipv6Addresses.isNotEmpty()) {
            return Result(
                server = server,
                attempted = true,
                selectedAddress = null,
                candidates = emptyList(),
                unresolvedHosts = endpoints.ipv6Addresses,
            )
        }

        val hosts = endpoints.hosts
        if (hosts.isEmpty()) {
            return Result(server, attempted = false, selectedAddress = null, candidates = emptyList())
        }

        val resolved = coroutineScope {
            hosts.map { host ->
                async { host to resolveHost(host, allowIpv6) }
            }.awaitAll()
        }
        val unresolvedHosts = resolved.filter { (_, candidates) -> candidates.isEmpty() }.map { it.first }
        val candidates = resolved.flatMap { it.second }.distinct()
        if (unresolvedHosts.isNotEmpty()) {
            return Result(
                server = server,
                attempted = true,
                selectedAddress = null,
                candidates = candidates,
                unresolvedHosts = unresolvedHosts,
            )
        }

        return Result(
            server = server.copy(bootstrapDnsHosts = resolved.toMap()),
            attempted = true,
            selectedAddress = candidates.firstOrNull(),
            candidates = candidates,
        )
    }

    private suspend fun resolveHost(host: String, allowIpv6: Boolean): List<String> {
        val candidates = hostLookup?.invoke(host) ?: systemLookup(host)
        return candidates.distinct().filter { allowIpv6 || !isIpv6Address(it) }
    }

    // DnsResolver and Dns.SYSTEM query the same netd resolver, so a second concurrent lookup adds no
    // information. DnsResolver is preferred because it is asynchronous and cancellable, which lets a
    // stalled query be abandoned after RESOLVE_TIMEOUT_MS; the blocking Dns.SYSTEM lookup is only a
    // fallback for that failure case and the primary path below Android 10.
    private suspend fun systemLookup(host: String): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val addresses = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveWithAndroidDns(host) }
            if (!addresses.isNullOrEmpty()) return addresses
        }
        return resolveWithOkHttpDns(host)
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

        dnsResolver().query(
            null,
            host,
            DnsResolver.FLAG_EMPTY,
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun dnsResolver(): DnsResolver = if (Build.VERSION.SDK_INT >= 37 && context != null) {
        DnsResolver(context, Looper.getMainLooper())
    } else {
        @Suppress("DEPRECATION")
        DnsResolver.getInstance()
    }

    private fun resolveWithOkHttpDns(host: String): List<String> = runCatching {
        Dns.SYSTEM.lookup(host).mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 2000L
        val ipv4Pattern = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }
}

internal fun rawProxyEndpointHosts(rawJson: String): List<String> = rawProxyEndpoints(rawJson).hosts

private data class RawProxyEndpoints(
    val hosts: List<String>,
    val ipv6Addresses: List<String>,
)

private fun rawProxyEndpoints(rawJson: String): RawProxyEndpoints {
    val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
        ?: return RawProxyEndpoints(emptyList(), emptyList())
    val outbounds = root["outbounds"] as? JsonArray ?: return RawProxyEndpoints(emptyList(), emptyList())
    val hosts = linkedSetOf<String>()
    val ipv6Addresses = linkedSetOf<String>()
    outbounds.mapNotNull { it as? JsonObject }.forEach { outbound ->
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (protocol in NON_PROXY_OUTBOUND_PROTOCOLS) return@forEach
        collectEndpoints(
            element = outbound,
            hosts = hosts,
            ipv6Addresses = ipv6Addresses,
            includeAddressFields = protocol != "wireguard",
        )
    }
    return RawProxyEndpoints(hosts.toList(), ipv6Addresses.toList())
}

private fun collectEndpoints(
    element: JsonElement,
    hosts: MutableSet<String>,
    ipv6Addresses: MutableSet<String>,
    includeAddressFields: Boolean,
    fieldName: String? = null,
) {
    when (element) {
        is JsonObject -> element.forEach { (name, value) ->
            collectEndpoints(value, hosts, ipv6Addresses, includeAddressFields, name)
        }
        is JsonArray -> element.forEach { value ->
            collectEndpoints(value, hosts, ipv6Addresses, includeAddressFields, fieldName)
        }
        is JsonPrimitive -> if (element.isString) {
            val endpoint = endpointAddress(fieldName, element.contentOrNull, includeAddressFields) ?: return
            when {
                isIpv6Literal(endpoint) -> ipv6Addresses += endpoint
                else -> endpointHostname(endpoint)?.let(hosts::add)
            }
        }
    }
}

private fun endpointAddress(fieldName: String?, value: String?, includeAddressFields: Boolean): String? {
    val endpoint = when (fieldName?.lowercase()) {
        "address" -> value?.takeIf { includeAddressFields }
        "endpoint" -> value
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        endpoint.startsWith('[') -> endpoint.substringAfter('[').substringBefore(']')
        endpoint.count { it == ':' } == 1 && endpoint.substringAfterLast(':').toIntOrNull() != null -> endpoint.substringBeforeLast(':')
        else -> endpoint.trim('[', ']')
    }
}

private fun endpointHostname(endpoint: String): String? {
    val candidate = endpoint.trimEnd('.').takeIf { it.isNotEmpty() } ?: return null
    if (candidate.equals("localhost", ignoreCase = true)) return null
    if (IPV4_ADDRESS.matches(candidate) && candidate.split('.').all { it.toIntOrNull() in 0..255 }) return null
    if (candidate.contains(':')) return null

    val ascii = runCatching { IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES) }.getOrNull() ?: return null
    return ascii.lowercase().takeIf { HOSTNAME.matches(it) }
}

private fun isIpv6Literal(value: String): Boolean = value.contains(':') &&
    runCatching { InetAddress.getByName(value.substringBefore('%')) is Inet6Address }.getOrDefault(false)

private val NON_PROXY_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")
private val IPV4_ADDRESS = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private val HOSTNAME = Regex("""(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*""")
