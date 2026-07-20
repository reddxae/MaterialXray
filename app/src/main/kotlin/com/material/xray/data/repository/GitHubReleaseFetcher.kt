package com.material.xray.data.repository

import com.material.xray.model.AppUpdateCheckStatus
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class GitHubRelease(
    val tagName: String,
    val apkDownloadUrl: String,
)

@Singleton
class GitHubReleaseFetcher @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun fetchLatestRelease(
        currentVersionName: String,
        onStatus: suspend (AppUpdateCheckStatus) -> Unit = {},
    ): GitHubRelease = withContext(Dispatchers.IO) {
        var lastFailure: Exception? = null
        val urls = githubMirrorUrls(GITHUB_API_URL)
        onStatus(AppUpdateCheckStatus.Fetching(urls.first()))
        for ((index, url) in urls.withIndex()) {
            try {
                val response = fetchRelease(url, currentVersionName)
                onStatus(AppUpdateCheckStatus.ReleaseReceived(url, response.statusCode))
                return@withContext response.release
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpStatusException) {
                lastFailure = error
                urls.getOrNull(index + 1)?.let { nextUrl ->
                    onStatus(AppUpdateCheckStatus.RetryingAfterHttpError(url, error.statusCode, nextUrl))
                }
            } catch (error: InvalidReleaseResponseException) {
                lastFailure = error
                urls.getOrNull(index + 1)?.let { nextUrl ->
                    onStatus(
                        AppUpdateCheckStatus.RetryingAfterInvalidResponse(
                            url = url,
                            statusCode = error.statusCode,
                            nextUrl = nextUrl,
                        ),
                    )
                }
            } catch (error: IOException) {
                lastFailure = error
                urls.getOrNull(index + 1)?.let { nextUrl ->
                    onStatus(AppUpdateCheckStatus.RetryingAfterConnectionFailure(url, nextUrl))
                }
            }
        }
        throw IOException("All GitHub release endpoints failed", lastFailure)
    }

    private fun fetchRelease(url: String, currentVersionName: String): ReleaseResponse {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MaterialXray/$currentVersionName")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code)
            }
            try {
                ReleaseResponse(
                    release = parseRelease(
                        release = json.parseToJsonElement(response.body.string()).jsonObject,
                        statusCode = response.code,
                    ),
                    statusCode = response.code,
                )
            } catch (error: IllegalArgumentException) {
                throw InvalidReleaseResponseException(
                    message = "GitHub release response was not valid JSON",
                    statusCode = response.code,
                    cause = error,
                )
            }
        }
    }

    private fun parseRelease(release: JsonObject, statusCode: Int): GitHubRelease {
        val tagName = release["tag_name"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw InvalidReleaseResponseException(
                message = "GitHub release response did not include a tag",
                statusCode = statusCode,
            )
        val apkDownloadUrl = release["assets"]
            ?.jsonArray
            ?.asSequence()
            ?.mapNotNull { asset ->
                asset.jsonObject["browser_download_url"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf(::isOfficialApkUrl)
            }
            ?.firstOrNull()
            ?: throw InvalidReleaseResponseException(
                message = "GitHub release response did not include an APK",
                statusCode = statusCode,
            )
        return GitHubRelease(tagName = tagName, apkDownloadUrl = apkDownloadUrl)
    }

    private fun isOfficialApkUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps &&
            url.host == "github.com" &&
            url.encodedPath.startsWith("/reddxae/MaterialXray/releases/download/") &&
            url.encodedPath.endsWith(".apk")
    }

    private companion object {
        const val GITHUB_API_URL = "https://api.github.com/repos/reddxae/MaterialXray/releases/latest"
    }

    private data class ReleaseResponse(
        val release: GitHubRelease,
        val statusCode: Int,
    )

    private class HttpStatusException(
        val statusCode: Int,
    ) : IOException("GitHub release request failed with HTTP $statusCode")

    private class InvalidReleaseResponseException(
        message: String,
        val statusCode: Int,
        cause: Throwable? = null,
    ) : IOException(message, cause)
}

internal fun githubMirrorUrls(officialUrl: String): List<String> = listOf(
    officialUrl,
    "https://ghfile.geekertao.top/$officialUrl",
    "https://github.dpik.top/$officialUrl",
    "https://gh.geekertao.top/$officialUrl",
)
