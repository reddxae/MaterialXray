package com.material.xray.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Proxy
import java.net.UnknownServiceException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CaptivePortalDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val baseClient: OkHttpClient,
) {
    sealed interface Result {
        data object Clear : Result
        data class Captive(val reason: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    suspend fun check(testUrl: String = DEFAULT_TEST_URL): Result = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return@withContext Result.Unavailable("Connectivity service is unavailable")
        val network = connectivityManager.selectPhysicalNetwork()
            ?: return@withContext Result.Unavailable("No active physical network is available")
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true) {
            return@withContext Result.Captive("Android reports that the current network requires sign-in")
        }

        val url = testUrl.toHttpUrlOrNull()
            ?: return@withContext Result.Unavailable("Captive portal check URL is invalid")
        if (url.isHttps) {
            return@withContext Result.Unavailable("Captive portal check URL must use HTTP")
        }

        val expectedHosts = expectedHosts(url.host)
        val client = baseClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(network.socketFactory)
            .dns(Dns { host -> network.getAllByName(host).toList() })
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CHECK_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Material Xray captive portal check")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url
                val finalHost = finalUrl.host.lowercase(Locale.US)
                if (finalHost !in expectedHosts) {
                    return@withContext Result.Captive(
                        "Captive portal detected: HTTP check was redirected to $finalHost",
                    )
                }
                if (response.code == HTTP_NETWORK_AUTHENTICATION_REQUIRED) {
                    return@withContext Result.Captive("Network returned HTTP 511 authentication required")
                }
                if (response.isRedirect) {
                    val location = response.header("Location").orEmpty().ifBlank { "unknown location" }
                    return@withContext Result.Captive("Captive portal detected: HTTP check redirected to $location")
                }
                if (response.code == HTTP_NO_CONTENT) {
                    return@withContext Result.Clear
                }

                val body = response.peekBody(MAX_RESPONSE_BYTES).string()
                if (body.contains(NEVERSSL_MARKER, ignoreCase = true)) {
                    Result.Clear
                } else if (response.isSuccessful) {
                    Result.Captive("Captive portal detected: HTTP check returned unexpected content")
                } else {
                    Result.Unavailable("HTTP check returned ${response.code}")
                }
            }
        }.getOrElse { error ->
            if (error is UnknownServiceException && error.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
                Result.Captive("Captive portal redirected the HTTP check to a blocked cleartext host")
            } else {
                Result.Unavailable(error.message ?: error::class.java.simpleName)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.selectPhysicalNetwork(): Network? {
        activeNetwork?.takeIf { it.isPhysicalInternetNetwork(this) }?.let { return it }
        return allNetworks.firstOrNull { it.isPhysicalInternetNetwork(this) }
    }

    private fun Network.isPhysicalInternetNetwork(connectivityManager: ConnectivityManager): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(this) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun expectedHosts(host: String): Set<String> {
        val normalized = host.lowercase(Locale.US)
        return if (normalized.startsWith("www.")) {
            setOf(normalized, normalized.removePrefix("www."))
        } else {
            setOf(normalized, "www.$normalized")
        }
    }

    private companion object {
        const val DEFAULT_TEST_URL = "http://connectivitycheck.gstatic.com/generate_204"
        const val NEVERSSL_MARKER = "NeverSSL"
        const val HTTP_NO_CONTENT = 204
        const val HTTP_NETWORK_AUTHENTICATION_REQUIRED = 511
        const val CHECK_TIMEOUT_SECONDS = 2L
        const val CHECK_CALL_TIMEOUT_SECONDS = 3L
        const val MAX_RESPONSE_BYTES = 64L * 1024L
    }
}
