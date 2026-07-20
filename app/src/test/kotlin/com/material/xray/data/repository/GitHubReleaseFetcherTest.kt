package com.material.xray.data.repository

import com.material.xray.model.AppUpdateCheckStatus
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubReleaseFetcherTest {
    @Test
    fun fallsBackAfterPrimaryEndpointFailure() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    response(
                        chain = chain,
                        code = if (requestedUrls.size == 1) 503 else 200,
                        body = if (requestedUrls.size == 1) "" else RELEASE_JSON,
                    )
                },
            )
            .build()
        val statuses = mutableListOf<AppUpdateCheckStatus>()

        val release = GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0") { statuses += it }

        assertEquals("v0.6.0", release.tagName)
        assertEquals(APK_URL, release.apkDownloadUrl)
        assertEquals(2, requestedUrls.size)
        assertEquals("api.github.com", URI(requestedUrls.first()).host)
        assertEquals("ghfile.geekertao.top", URI(requestedUrls.last()).host)
        assertEquals(
            listOf(
                AppUpdateCheckStatus.Fetching(requestedUrls.first()),
                AppUpdateCheckStatus.RetryingAfterHttpError(
                    url = requestedUrls.first(),
                    statusCode = 503,
                    nextUrl = requestedUrls.last(),
                ),
                AppUpdateCheckStatus.ReleaseReceived(requestedUrls.last(), 200),
            ),
            statuses,
        )
    }

    @Test
    fun reportsConnectionFailureWhileTryingFallback() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    if (requestedUrls.size == 1) throw IOException("Connection refused")
                    response(chain, 200, RELEASE_JSON)
                },
            )
            .build()
        val statuses = mutableListOf<AppUpdateCheckStatus>()

        GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0") { statuses += it }

        assertEquals(
            listOf(
                AppUpdateCheckStatus.Fetching(requestedUrls.first()),
                AppUpdateCheckStatus.RetryingAfterConnectionFailure(
                    url = requestedUrls.first(),
                    nextUrl = requestedUrls.last(),
                ),
                AppUpdateCheckStatus.ReleaseReceived(requestedUrls.last(), 200),
            ),
            statuses,
        )
    }

    @Test
    fun reportsInvalidReleaseDataWhileTryingFallback() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    response(
                        chain = chain,
                        code = 200,
                        body = if (requestedUrls.size == 1) "{}" else RELEASE_JSON,
                    )
                },
            )
            .build()
        val statuses = mutableListOf<AppUpdateCheckStatus>()

        GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0") { statuses += it }

        assertEquals(
            listOf(
                AppUpdateCheckStatus.Fetching(requestedUrls.first()),
                AppUpdateCheckStatus.RetryingAfterInvalidResponse(
                    url = requestedUrls.first(),
                    statusCode = 200,
                    nextUrl = requestedUrls.last(),
                ),
                AppUpdateCheckStatus.ReleaseReceived(requestedUrls.last(), 200),
            ),
            statuses,
        )
    }

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Unavailable")
        .body(body.toResponseBody())
        .build()

    private companion object {
        const val APK_URL =
            "https://github.com/reddxae/MaterialXray/releases/download/v0.6.0/MaterialXray-v0.6.0.apk"
        const val RELEASE_JSON = "{\"tag_name\":\"v0.6.0\",\"assets\":[{\"browser_download_url\":\"$APK_URL\"}]}"
    }
}
