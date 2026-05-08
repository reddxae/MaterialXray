package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class XrayBinaryTest {

    @Test
    fun `ensureExtracted extracts arm64 binary and records app version`() = withTempDir { dir ->
        val environment = FakeEnvironment(
            filesDir = dir,
            assets = mapOf("xray_arm64" to "binary-v1"),
            version = "1.0",
        )
        val xrayBinary = XrayBinary(environment, supportedAbis = { arrayOf("arm64-v8a") })

        assertTrue(xrayBinary.ensureRootBinaryExtracted())

        assertEquals("binary-v1", File(dir, "bin/xray").readText())
        assertEquals("1.0", File(dir, "bin/version").readText())
        assertTrue(File(xrayBinary.rootBinaryPath).canExecute())
    }

    @Test
    fun `ensureExtracted skips extraction when binary and version are current`() = withTempDir { dir ->
        val binDir = File(dir, "bin").apply { mkdirs() }
        File(binDir, "xray").writeText("existing")
        File(binDir, "xray").setExecutable(true, false)
        File(binDir, "version").writeText("1.0")
        val environment = FakeEnvironment(
            filesDir = dir,
            assets = mapOf("xray_arm64" to "replacement"),
            version = "1.0",
        )

        assertTrue(XrayBinary(environment, supportedAbis = { arrayOf("arm64-v8a") }).ensureRootBinaryExtracted())

        assertEquals("existing", File(binDir, "xray").readText())
        assertFalse(environment.openedAssets.contains("xray_arm64"))
    }

    @Test
    fun `ensureAndroidBinaryAvailable uses native library executable when available`() = withTempDir { dir ->
        val nativeDir = File(dir, "lib").apply { mkdirs() }
        val nativeBinary = File(nativeDir, "libxray.so").apply {
            writeText("native")
            setExecutable(true, false)
        }
        val environment = FakeEnvironment(
            filesDir = dir,
            nativeLibraryDir = nativeDir,
            assets = mapOf("xray_arm64" to "asset"),
            version = "1.0",
        )
        val xrayBinary = XrayBinary(environment, supportedAbis = { arrayOf("arm64-v8a") })

        assertTrue(xrayBinary.ensureAndroidBinaryAvailable())

        assertEquals(nativeBinary.absolutePath, xrayBinary.androidBinaryPath)
        assertTrue(File(dir, "bin").isDirectory)
        assertFalse(File(dir, "bin/xray").exists())
        assertFalse(environment.openedAssets.contains("xray_arm64"))
    }

    @Test
    fun `ensureExtracted returns false for unsupported abi`() = withTempDir { dir ->
        val environment = FakeEnvironment(filesDir = dir)

        assertFalse(XrayBinary(environment, supportedAbis = { arrayOf("armeabi-v7a") }).ensureRootBinaryExtracted())
        assertFalse(File(dir, "bin/xray").exists())
    }

    @Test
    fun `writeConfig writes config to files dir`() = withTempDir { dir ->
        val xrayBinary = XrayBinary(FakeEnvironment(filesDir = dir), supportedAbis = { arrayOf("arm64-v8a") })

        xrayBinary.writeConfig("""{"log":{}}""")

        assertEquals(File(dir, "config.json").absolutePath, xrayBinary.configPath())
        assertEquals("""{"log":{}}""", File(dir, "config.json").readText())
    }

    private class FakeEnvironment(
        override val filesDir: File,
        override val nativeLibraryDir: File? = null,
        private val assets: Map<String, String> = emptyMap(),
        private val version: String = "test",
    ) : XrayBinaryEnvironment {
        val openedAssets = mutableListOf<String>()

        override fun openAsset(name: String): InputStream {
            openedAssets += name
            return ByteArrayInputStream(assets.getValue(name).toByteArray())
        }

        override fun appVersion(): String = version
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("xray-binary-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
