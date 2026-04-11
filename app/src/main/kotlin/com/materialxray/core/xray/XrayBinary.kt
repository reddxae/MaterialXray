package com.materialxray.core.xray

import android.content.Context
import android.os.Build
import java.io.File

class XrayBinary(private val context: Context) {

    private val binaryDir = File(context.filesDir, "bin")
    val binaryPath: String get() = File(binaryDir, "xray").absolutePath

    fun ensureExtracted(): Boolean {
        binaryDir.mkdirs()

        val assetName = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "xray_arm64"
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "xray_x86_64"
            else -> return false
        }

        val versionFile = File(binaryDir, "version")
        val currentVersion = getAppVersion()
        val needsExtract = !File(binaryDir, "xray").exists() ||
            !versionFile.exists() ||
            versionFile.readText() != currentVersion

        if (needsExtract) {
            if (!extractAsset(assetName, "xray", executable = true)) return false
            versionFile.writeText(currentVersion)
        }

        return File(binaryDir, "xray").let { it.exists() && it.canExecute() }
    }

    fun configPath(): String = File(context.filesDir, "config.json").absolutePath

    fun writeConfig(configJson: String) {
        File(context.filesDir, "config.json").writeText(configJson)
    }

    private fun extractAsset(assetName: String, targetName: String, executable: Boolean): Boolean =
        runCatching {
            val target = File(binaryDir, targetName)
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (executable) target.setExecutable(true, false)
            true
        }.getOrDefault(false)

    private fun getAppVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
