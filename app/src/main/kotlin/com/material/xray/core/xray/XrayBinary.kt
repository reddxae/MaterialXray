package com.material.xray.core.xray

import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream

internal interface XrayBinaryEnvironment {
    val filesDir: File
    val nativeLibraryDir: File?
    fun openAsset(name: String): InputStream
    fun appVersion(): String
}

internal class AndroidXrayBinaryEnvironment(
    private val context: Context,
) : XrayBinaryEnvironment {
    override val filesDir: File
        get() = context.filesDir

    override val nativeLibraryDir: File?
        get() = context.applicationInfo.nativeLibraryDir?.let(::File)

    override fun openAsset(name: String): InputStream = context.assets.open(name)

    override fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}

class XrayBinary internal constructor(
    private val environment: XrayBinaryEnvironment,
    private val supportedAbis: () -> Array<String>,
) {
    constructor(context: Context) : this(
        environment = AndroidXrayBinaryEnvironment(context),
        supportedAbis = { Build.SUPPORTED_ABIS },
    )

    private val binaryDir = File(environment.filesDir, "bin")
    val rootBinaryPath: String get() = File(binaryDir, "xray").absolutePath
    val androidBinaryPath: String?
        get() = environment.nativeLibraryDir
            ?.resolve("libxray.so")
            ?.takeIf { it.isFile && it.canExecute() }
            ?.absolutePath

    fun ensureRootBinaryExtracted(): Boolean {
        binaryDir.mkdirs()

        val assetName = when {
            supportedAbis().any { it == "arm64-v8a" } -> "xray_arm64"
            supportedAbis().any { it == "x86_64" } -> "xray_x86_64"
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

    fun ensureAndroidBinaryAvailable(): Boolean {
        binaryDir.mkdirs()
        return androidBinaryPath != null
    }

    fun configPath(): String = File(environment.filesDir, "config.json").absolutePath

    fun writeConfig(configJson: String) {
        File(environment.filesDir, "config.json").writeText(configJson)
    }

    private fun extractAsset(assetName: String, targetName: String, executable: Boolean): Boolean =
        runCatching {
            val target = File(binaryDir, targetName)
            environment.openAsset(assetName).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (executable) target.setExecutable(true, false)
            true
        }.getOrDefault(false)

    private fun getAppVersion(): String = environment.appVersion()
}
