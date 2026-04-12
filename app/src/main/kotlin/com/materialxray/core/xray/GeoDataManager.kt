package com.materialxray.core.xray

import android.content.Context
import com.materialxray.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal const val GEOIP_FILE_NAME = "geoip.dat"
internal const val GEOSITE_FILE_NAME = "geosite.dat"

internal fun normalizeGeoDataUrl(url: String): String = url.trim()

data class GeoDataStatus(
    val geoipUrl: String,
    val geositeUrl: String,
    val downloaded: Boolean,
)

@Singleton
class GeoDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val binaryDir get() = File(context.filesDir, "bin")
    private val geoipSourceFile get() = File(binaryDir, "geoip-source")
    private val geositeSourceFile get() = File(binaryDir, "geosite-source")

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
        }

        GeoDataStatus(
            geoipUrl = state.geoipUrl,
            geositeUrl = state.geositeUrl,
            downloaded = state.needsDownload,
        )
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
