package com.material.xray.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.material.xray.core.root.RootShell
import com.material.xray.data.repository.GitHubReleaseFetcher
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.githubMirrorUrls
import com.material.xray.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class AppUpdateInstallStage {
    ResolvingRelease,
    Connecting,
    Downloading,
    Verifying,
    PreparingInstallation,
    OpeningInstaller,
    InstallingWithRoot,
}

data class AppUpdateInstallProgress(
    val stage: AppUpdateInstallStage,
    val fraction: Float?,
)

@Singleton
class AppUpdateInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val releaseFetcher: GitHubReleaseFetcher,
    private val settingsRepository: SettingsRepository,
    private val rootShell: RootShell,
) {
    private val installMutex = Mutex()
    private val _installProgress = MutableStateFlow<AppUpdateInstallProgress?>(null)
    private val _installPermissionRationaleRequired = MutableStateFlow(false)
    private val updateFile = context.cacheDir.resolve("updates/MaterialXray-update.apk")
    private val currentVersionName = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    @Volatile private var waitingForInstallPermission = false

    val installProgress: StateFlow<AppUpdateInstallProgress?> = _installProgress.asStateFlow()
    val installPermissionRationaleRequired: StateFlow<Boolean> = _installPermissionRationaleRequired.asStateFlow()

    suspend fun install(update: AppUpdate) {
        if (!installMutex.tryLock()) return
        _installProgress.value = AppUpdateInstallProgress(
            stage = if (update.apkDownloadUrl == null) {
                AppUpdateInstallStage.ResolvingRelease
            } else {
                AppUpdateInstallStage.Connecting
            },
            fraction = null,
        )
        try {
            val downloadUrl = update.apkDownloadUrl ?: releaseFetcher
                .fetchLatestRelease(currentVersionName)
                .apkDownloadUrl
            val apk = withContext(Dispatchers.IO) { downloadApk(downloadUrl) }
            _installProgress.value = AppUpdateInstallProgress(
                stage = AppUpdateInstallStage.PreparingInstallation,
                fraction = null,
            )
            if (settingsRepository.useRootService.first()) {
                installWithRoot(apk)
            } else {
                withContext(Dispatchers.Main) { requestInstall(apk) }
            }
        } finally {
            _installProgress.value = null
            installMutex.unlock()
        }
    }

    fun resumePendingInstall() {
        if (!waitingForInstallPermission || !context.packageManager.canRequestPackageInstalls()) return
        waitingForInstallPermission = false
        updateFile.takeIf(File::isFile)?.let(::launchPackageInstaller)
    }

    fun confirmInstallPermissionRationale() {
        _installPermissionRationaleRequired.value = false
        context.startActivity(installPermissionIntent())
    }

    fun dismissInstallPermissionRationale() {
        _installPermissionRationaleRequired.value = false
        waitingForInstallPermission = false
    }

    private fun downloadApk(officialUrl: String): File {
        var lastFailure: IOException? = null
        for (url in githubMirrorUrls(officialUrl)) {
            try {
                return downloadApkFrom(url)
            } catch (error: IOException) {
                lastFailure = error
            }
        }
        throw IOException("All app update download endpoints failed", lastFailure)
    }

    private fun downloadApkFrom(url: String): File {
        _installProgress.value = AppUpdateInstallProgress(
            stage = AppUpdateInstallStage.Connecting,
            fraction = null,
        )
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MaterialXray/$currentVersionName")
            .build()
        val parent = updateCacheDirectory()
        val temporaryFile = parent.resolve("${updateFile.name}.download")

        try {
            client.newCall(request).execute().use { response ->
                validateDownloadResponse(response)
                writeApk(response.body.byteStream(), response.body.contentLength(), temporaryFile)
            }
            _installProgress.value = AppUpdateInstallProgress(
                stage = AppUpdateInstallStage.Verifying,
                fraction = null,
            )
            validateApk(temporaryFile)
            replaceCachedApk(temporaryFile)
            return updateFile
        } catch (error: IOException) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun updateCacheDirectory(): File {
        val parent = updateFile.parentFile ?: throw IOException("Update cache is unavailable")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create update cache")
        return parent
    }

    private fun validateDownloadResponse(response: Response) {
        if (!response.isSuccessful || !response.request.url.isHttps) {
            throw IOException("App update download failed with HTTP ${response.code}")
        }
    }

    private fun writeApk(input: InputStream, contentLength: Long, destination: File) {
        if (contentLength > MAX_APK_SIZE_BYTES) throw IOException("App update is too large")
        _installProgress.value = AppUpdateInstallProgress(
            stage = AppUpdateInstallStage.Downloading,
            fraction = contentLength.takeIf { it > 0L }?.let { 0f },
        )
        input.use {
            destination.outputStream().use { output -> copyApk(input, output, contentLength) }
        }
    }

    private fun copyApk(input: InputStream, output: OutputStream, contentLength: Long) {
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
        var totalBytes = 0L
        var lastReportedBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                if (contentLength > 0L) {
                    _installProgress.value = AppUpdateInstallProgress(
                        stage = AppUpdateInstallStage.Downloading,
                        fraction = 1f,
                    )
                }
                return
            }
            totalBytes += read
            if (totalBytes > MAX_APK_SIZE_BYTES) throw IOException("App update is too large")
            output.write(buffer, 0, read)
            if (contentLength > 0L && totalBytes - lastReportedBytes >= PROGRESS_UPDATE_BYTES) {
                _installProgress.value = AppUpdateInstallProgress(
                    stage = AppUpdateInstallStage.Downloading,
                    fraction = (totalBytes.toFloat() / contentLength).coerceIn(0f, 1f),
                )
                lastReportedBytes = totalBytes
            }
        }
    }

    private fun validateApk(file: File) {
        @Suppress("DEPRECATION")
        val archivePackage = context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
        if (archivePackage != context.packageName) throw IOException("Downloaded file is not a Material Xray APK")
    }

    private fun replaceCachedApk(temporaryFile: File) {
        if (updateFile.exists() && !updateFile.delete()) throw IOException("Unable to replace cached app update")
        if (!temporaryFile.renameTo(updateFile)) throw IOException("Unable to cache app update")
    }

    private fun requestInstall(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = true
            _installPermissionRationaleRequired.value = true
            return
        }
        _installProgress.value = AppUpdateInstallProgress(
            stage = AppUpdateInstallStage.OpeningInstaller,
            fraction = null,
        )
        launchPackageInstaller(apk)
    }

    private fun launchPackageInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private suspend fun installWithRoot(apk: File) {
        _installProgress.value = AppUpdateInstallProgress(
            stage = AppUpdateInstallStage.InstallingWithRoot,
            fraction = null,
        )
        val result = rootShell.execute(
            command = rootInstallCommand(apk.path),
            namespace = RootShell.NetworkNamespace.CURRENT,
            timeoutMs = ROOT_INSTALL_TIMEOUT_MILLIS,
        )
        if (!result.isSuccess) {
            throw IOException(result.error.ifBlank { result.output.ifBlank { "Root package installation failed" } })
        }
    }

    private fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val MAX_APK_SIZE_BYTES = 200L * 1024 * 1024
        const val DOWNLOAD_BUFFER_SIZE_BYTES = 16 * 1024
        const val PROGRESS_UPDATE_BYTES = 256 * 1024L
        const val ROOT_INSTALL_TIMEOUT_MILLIS = 120_000L
    }
}

internal fun rootInstallCommand(apkPath: String): String {
    val source = shellQuote(apkPath)
    val target = shellQuote("/data/local/tmp/MaterialXray-update.apk")
    return "cp $source $target && chmod 0644 $target && pm install -r $target; " +
        "status=\$?; rm -f $target; test \$status -eq 0"
}
