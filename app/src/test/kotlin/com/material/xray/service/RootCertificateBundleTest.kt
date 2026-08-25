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
    fun `bundle is reused after it is generated in this process`() = runTest {
        val directory = Files.createTempDirectory("root-ca-bundle-test").toFile()
        val bundleFile = directory.resolve("ca-certificates.pem")
        var loads = 0

        try {
            val bundle = AndroidRootCertificateBundle {
                loads++
                listOf(byteArrayOf(1, 2, 3))
            }
            bundle.update(bundleFile)
            bundle.update(bundleFile)

            assertEquals(1, loads)
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
