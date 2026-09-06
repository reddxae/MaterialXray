package com.material.xray.core.xray

import android.content.Context
import com.material.xray.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val GEOIP_FILE_NAME = "geoip.dat"
internal const val GEOSITE_FILE_NAME = "geosite.dat"

/** Assets older than this are re-downloaded by the periodic background refresh. */
internal const val GEO_DATA_MAX_AGE_MS = 24L * 60 * 60 * 1000

internal fun normalizeGeoDataUrl(url: String): String = url.trim()

internal fun isGeoDataStale(updatedAtMillis: Long?, nowMillis: Long): Boolean = updatedAtMillis == null || nowMillis - updatedAtMillis >= GEO_DATA_MAX_AGE_MS

data class GeoDataStatus(
    val geoipUrl: String,
    val geositeUrl: String,
    val downloaded: Boolean,
)

enum class GeoDataAsset {
    GEOIP,
    GEOSITE,
}

@Singleton
class GeoDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val binaryDir get() = File(context.filesDir, "bin")
    private val geoipSourceFile get() = File(binaryDir, "geoip-source")
    private val geositeSourceFile get() = File(binaryDir, "geosite-source")
    private val geoipUpdatedAtFile get() = File(binaryDir, "geoip-updated-at")
    private val geositeUpdatedAtFile get() = File(binaryDir, "geosite-updated-at")

    suspend fun needsRefresh(): Boolean = withContext(Dispatchers.IO) {
        resolveState().needsDownload
    }

    suspend fun ensureReady(): GeoDataStatus = withContext(Dispatchers.IO) {
        binaryDir.mkdirs()
        val state = resolveState()

        if (state.needsDownload) {
            coroutineScope {
                val geoipDownload = async { download(state.geoipUrl, state.geoipFile) }
                val geositeDownload = async { download(state.geositeUrl, state.geositeFile) }
                geoipDownload.await()
                geositeDownload.await()
            }
            geoipSourceFile.writeText(state.geoipUrl)
            geositeSourceFile.writeText(state.geositeUrl)
            markUpdated(geoipUpdatedAtFile)
            markUpdated(geositeUpdatedAtFile)
        }

        GeoDataStatus(
            geoipUrl = state.geoipUrl,
            geositeUrl = state.geositeUrl,
            downloaded = state.needsDownload,
        )
    }

    suspend fun refresh(asset: GeoDataAsset) = withContext(Dispatchers.IO) {
        binaryDir.mkdirs()
        val state = resolveState()
        when (asset) {
            GeoDataAsset.GEOIP -> {
                download(state.geoipUrl, state.geoipFile)
                geoipSourceFile.writeText(state.geoipUrl)
                markUpdated(geoipUpdatedAtFile)
            }
            GeoDataAsset.GEOSITE -> {
                download(state.geositeUrl, state.geositeFile)
                geositeSourceFile.writeText(state.geositeUrl)
                markUpdated(geositeUpdatedAtFile)
            }
        }
    }

    /**
     * Re-downloads each asset that is missing, was fetched with a different URL, or is older than
     * [GEO_DATA_MAX_AGE_MS]. Only files on disk are replaced: a running core keeps serving its
     * in-memory data and picks up the fresh files at its next start, so no reload is triggered.
     */
    suspend fun refreshIfStale() = withContext(Dispatchers.IO) {
        binaryDir.mkdirs()
        val state = resolveState()
        coroutineScope {
            val geoip = async {
                runCatching {
                    refreshIfDue(state.geoipUrl, state.geoipFile, geoipSourceFile, geoipUpdatedAtFile)
                }
            }
            val geosite = async {
                runCatching {
                    refreshIfDue(state.geositeUrl, state.geositeFile, geositeSourceFile, geositeUpdatedAtFile)
                }
            }
            val failures = listOfNotNull(geoip.await().exceptionOrNull(), geosite.await().exceptionOrNull())
            if (failures.isNotEmpty()) throw failures.first()
        }
    }

    private fun refreshIfDue(url: String, targetFile: File, sourceMarkerFile: File, updatedAtFile: File) {
        val updatedAt = updatedAtFile.readTextOrNull()?.toLongOrNull()
        val isDue = sourceMarkerFile.readTextOrNull() != url ||
            !targetFile.exists() ||
            isGeoDataStale(updatedAt, System.currentTimeMillis())
        if (!isDue) return
        download(url, targetFile)
        sourceMarkerFile.writeText(url)
        markUpdated(updatedAtFile)
    }

    private fun markUpdated(updatedAtFile: File) {
        updatedAtFile.writeText(System.currentTimeMillis().toString())
    }

    private fun download(sourceUrl: String, targetFile: File) {
        val normalizedUrl = normalizeGeoDataUrl(sourceUrl)
        normalizedUrl.toHttpUrlOrNull() ?: throw IOException("Invalid geo data URL: $normalizedUrl")
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download")
        val request = Request.Builder().url(normalizedUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download ${targetFile.name}: HTTP ${response.code}")
            }

            val responseBody = response.body
            responseBody.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun File.readTextOrNull(): String? = takeIf(File::exists)?.readText()?.trim()

    private suspend fun resolveState(): ResolvedGeoDataState {
        binaryDir.mkdirs()

        val configuredGeoipUrl = normalizeGeoDataUrl(settingsRepository.geoipUrl.first())
        val configuredGeositeUrl = normalizeGeoDataUrl(settingsRepository.geositeUrl.first())
        val geoipUrl = configuredGeoipUrl.ifEmpty { SettingsRepository.DEFAULT_GEOIP_URL }
        val geositeUrl = configuredGeositeUrl.ifEmpty { SettingsRepository.DEFAULT_GEOSITE_URL }
        val geoipFile = File(binaryDir, GEOIP_FILE_NAME)
        val geositeFile = File(binaryDir, GEOSITE_FILE_NAME)
        val needsDownload = geoipSourceFile.readTextOrNull() != geoipUrl ||
            geositeSourceFile.readTextOrNull() != geositeUrl ||
            !geoipFile.exists() ||
            !geositeFile.exists()

        return ResolvedGeoDataState(
            geoipUrl = geoipUrl,
            geositeUrl = geositeUrl,
            geoipFile = geoipFile,
            geositeFile = geositeFile,
            needsDownload = needsDownload,
        )
    }

    private data class ResolvedGeoDataState(
        val geoipUrl: String,
        val geositeUrl: String,
        val geoipFile: File,
        val geositeFile: File,
        val needsDownload: Boolean,
    )
}
