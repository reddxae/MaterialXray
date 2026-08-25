package com.material.xray.service

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface RootCertificateBundle {
    suspend fun update(file: File)
}

internal class AndroidRootCertificateBundle(
    private val loadCertificates: () -> List<ByteArray> = ::loadAndroidCaCertificates,
) : RootCertificateBundle {
    private val generatedInProcess = AtomicBoolean()

    // Enumerating the Android CA store and rewriting the bundle happens on every root connect, so
    // it must not run on whichever thread issued the connection command.
    override suspend fun update(file: File) {
        withContext(Dispatchers.IO) {
            if (generatedInProcess.get() && file.isFile && file.length() > 0L) return@withContext
            val certificates = loadCertificates()
            require(certificates.isNotEmpty()) { "Android CA store contains no certificates" }

            val parent = requireNotNull(file.parentFile) { "Certificate bundle must have a parent directory" }
            check(parent.isDirectory || parent.mkdirs()) { "Could not create certificate bundle directory: $parent" }
            val temporaryFile = File.createTempFile("${file.name}.", ".tmp", parent)

            try {
                temporaryFile.bufferedWriter(StandardCharsets.US_ASCII).use { writer ->
                    certificates.forEach { certificate ->
                        writer.appendLine("-----BEGIN CERTIFICATE-----")
                        writer.appendLine(PEM_ENCODER.encodeToString(certificate))
                        writer.appendLine("-----END CERTIFICATE-----")
                    }
                }
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                generatedInProcess.set(true)
            } finally {
                temporaryFile.delete()
            }
        }
    }

    private companion object {
        private val PEM_ENCODER = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
    }
}

private fun loadAndroidCaCertificates(): List<ByteArray> {
    val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val aliases = buildList {
        val enumeration = keyStore.aliases()
        while (enumeration.hasMoreElements()) {
            val alias = enumeration.nextElement()
            if (isAndroidSystemCaAlias(alias)) add(alias)
        }
    }
    return aliases.sorted().mapNotNull { alias ->
        (keyStore.getCertificate(alias) as? X509Certificate)?.encoded
    }
}

internal fun isAndroidSystemCaAlias(alias: String): Boolean = alias.startsWith("system:")
