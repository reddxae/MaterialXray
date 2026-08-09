package com.material.xray.service

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCertificateBundleTest {

    @Test
    fun `writes certificates as an atomic PEM bundle`() = runTest {
        val directory = Files.createTempDirectory("root-ca-bundle-test").toFile()
        val bundleFile = directory.resolve("ca-certificates.pem")

        try {
            AndroidRootCertificateBundle {
                listOf(
                    byteArrayOf(1, 2, 3),
                    byteArrayOf(4, 5, 6),
                )
            }.update(bundleFile)

            assertEquals(
                """
                -----BEGIN CERTIFICATE-----
                AQID
                -----END CERTIFICATE-----
                -----BEGIN CERTIFICATE-----
                BAUG
                -----END CERTIFICATE-----

                """.trimIndent(),
                bundleFile.readText(),
            )
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty Android CA store leaves existing bundle unchanged`() = runTest {
        val directory = Files.createTempDirectory("root-ca-bundle-test").toFile()
        val bundleFile = directory.resolve("ca-certificates.pem").apply { writeText("existing") }

        try {
            val failure = runCatching { AndroidRootCertificateBundle { emptyList() }.update(bundleFile) }.exceptionOrNull()

            assertEquals("Android CA store contains no certificates", failure?.message)
            assertEquals("existing", bundleFile.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `only Android system CA aliases are selected`() {
        assertTrue(isAndroidSystemCaAlias("system:12345678.0"))
        assertFalse(isAndroidSystemCaAlias("user:12345678.0"))
    }
}
