package com.material.xray.data.repository

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

    internal suspend fun fetchLatestRelease(currentVersionName: String): GitHubRelease = withContext(Dispatchers.IO) {
        var lastFailure: Exception? = null
        for (url in githubMirrorUrls(GITHUB_API_URL)) {
            try {
                return@withContext fetchRelease(url, currentVersionName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastFailure = error
            } catch (error: IllegalArgumentException) {
                lastFailure = error
            }
        }
        throw IOException("All GitHub release endpoints failed", lastFailure)
    }

    private fun fetchRelease(url: String, currentVersionName: String): GitHubRelease {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MaterialXray/$currentVersionName")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub release request failed with HTTP ${response.code}")
            }
            parseRelease(json.parseToJsonElement(response.body.string()).jsonObject)
        }
    }

    private fun parseRelease(release: JsonObject): GitHubRelease {
        val tagName = release["tag_name"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw IOException("GitHub release response did not include a tag")
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
            ?: throw IOException("GitHub release response did not include an APK")
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
}

internal fun githubMirrorUrls(officialUrl: String): List<String> = listOf(
    officialUrl,
    "https://ghfile.geekertao.top/$officialUrl",
    "https://github.dpik.top/$officialUrl",
    "https://gh.geekertao.top/$officialUrl",
)
