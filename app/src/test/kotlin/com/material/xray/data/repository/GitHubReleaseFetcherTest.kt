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
import org.junit.Assert.assertNotNull
import org.junit.Test

class GitHubReleaseFetcherTest {
    @Test
    fun resolvesTransferredRepositoryById() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    if (requestedUrls.size == 1) {
                        response(chain, 200, REPOSITORY_JSON)
                    } else {
                        response(chain, 200, RELEASE_JSON)
                    }
                },
            )
            .build()

        val release = GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0")

        assertEquals("v0.6.0", release.tagName)
        assertEquals(APK_URL, release.apkDownloadUrl)
        assertEquals(
            listOf(
                "https://api.github.com/repositories/1208039570",
                "https://api.github.com/repos/AetherMagee/MaterialXray/releases/latest",
            ),
            requestedUrls,
        )
    }

    @Test
    fun doesNotTrustMirrorsToResolveRepositoryIdentity() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    response(chain, 503, "")
                },
            )
            .build()
        var failure: IOException? = null

        try {
            GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0")
        } catch (error: IOException) {
            failure = error
        }

        assertNotNull(failure)
        assertEquals(listOf("https://api.github.com/repositories/1208039570"), requestedUrls)
    }

    @Test
    fun rejectsApkFromRepositoryOtherThanResolvedIdentity() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    val body = if (requestedUrls.size == 1) REPOSITORY_JSON else FOREIGN_RELEASE_JSON
                    response(chain, 200, body)
                },
            )
            .build()
        var failure: IOException? = null

        try {
            GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0")
        } catch (error: IOException) {
            failure = error
        }

        assertNotNull(failure)
        assertEquals(5, requestedUrls.size)
    }

    @Test
    fun fallsBackAfterPrimaryEndpointFailure() = runTest {
        val requestedUrls = mutableListOf<String>()
        var releaseRequestCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    if (chain.request().url.encodedPath == "/repositories/1208039570") {
                        return@Interceptor response(chain, 200, REPOSITORY_JSON)
                    }
                    releaseRequestCount++
                    response(
                        chain = chain,
                        code = if (releaseRequestCount == 1) 503 else 200,
                        body = if (releaseRequestCount == 1) "" else RELEASE_JSON,
                    )
                },
            )
            .build()
        val statuses = mutableListOf<AppUpdateCheckStatus>()

        val release = GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0") { statuses += it }

        assertEquals("v0.6.0", release.tagName)
        assertEquals(APK_URL, release.apkDownloadUrl)
        assertEquals(3, requestedUrls.size)
        assertEquals("api.github.com", URI(requestedUrls[1]).host)
        assertEquals("ghfile.geekertao.top", URI(requestedUrls.last()).host)
        assertEquals(
            listOf(
                AppUpdateCheckStatus.Fetching(requestedUrls[1]),
                AppUpdateCheckStatus.RetryingAfterHttpError(
                    url = requestedUrls[1],
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
        var releaseRequestCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    if (chain.request().url.encodedPath == "/repositories/1208039570") {
                        return@Interceptor response(chain, 200, REPOSITORY_JSON)
                    }
                    releaseRequestCount++
                    if (releaseRequestCount == 1) throw IOException("Connection refused")
                    response(chain, 200, RELEASE_JSON)
                },
            )
            .build()
        val statuses = mutableListOf<AppUpdateCheckStatus>()

        GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0") { statuses += it }

        assertEquals(
            listOf(
                AppUpdateCheckStatus.Fetching(requestedUrls[1]),
                AppUpdateCheckStatus.RetryingAfterConnectionFailure(
                    url = requestedUrls[1],
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
        var releaseRequestCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrls += chain.request().url.toString()
                    if (chain.request().url.encodedPath == "/repositories/1208039570") {
                        return@Interceptor response(chain, 200, REPOSITORY_JSON)
                    }
                    releaseRequestCount++
                    response(
                        chain = chain,
                        code = 200,
                        body = if (releaseRequestCount == 1) "{}" else RELEASE_JSON,
                    )
                },
            )
            .build()
        val statuses = mutableListOf<AppUpdateCheckStatus>()

        GitHubReleaseFetcher(client).fetchLatestRelease("0.5.0") { statuses += it }

        assertEquals(
            listOf(
                AppUpdateCheckStatus.Fetching(requestedUrls[1]),
                AppUpdateCheckStatus.RetryingAfterInvalidResponse(
                    url = requestedUrls[1],
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
            "https://github.com/AetherMagee/MaterialXray/releases/download/v0.6.0/MaterialXray-v0.6.0.apk"
        const val REPOSITORY_JSON = "{\"id\":1208039570,\"full_name\":\"AetherMagee/MaterialXray\"}"
        const val RELEASE_JSON = "{\"tag_name\":\"v0.6.0\",\"assets\":[{\"browser_download_url\":\"$APK_URL\"}]}"
        const val FOREIGN_RELEASE_JSON =
            "{\"tag_name\":\"v0.6.0\",\"assets\":[{\"browser_download_url\":" +
                "\"https://github.com/attacker/MaterialXray/releases/download/v0.6.0/update.apk\"}]}"
    }
}
