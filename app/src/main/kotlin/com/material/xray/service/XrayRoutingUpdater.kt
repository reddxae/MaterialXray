package com.material.xray.service

import com.material.xray.core.xray.XRAY_API_TIMEOUT_MS
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.cliServerAddress
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface XrayRoutingUpdateResult {
    data object Applied : XrayRoutingUpdateResult

    data class Failed(val reason: String) : XrayRoutingUpdateResult
}

internal fun interface ConnectionXrayRoutingUpdater {
    suspend fun replace(endpoint: XrayApiEndpoint, routing: JsonObject): XrayRoutingUpdateResult
}

internal class XrayCliRoutingUpdater(
    private val binaryPath: () -> String?,
    private val binDir: String,
) : ConnectionXrayRoutingUpdater {
    override suspend fun replace(endpoint: XrayApiEndpoint, routing: JsonObject): XrayRoutingUpdateResult = withContext(Dispatchers.IO) {
        replaceOnIoThread(endpoint, routing)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun replaceOnIoThread(endpoint: XrayApiEndpoint, routing: JsonObject): XrayRoutingUpdateResult {
        val server = endpoint.cliServerAddress()
            ?: return XrayRoutingUpdateResult.Failed("the active API endpoint is not CLI-compatible")
        val executable = binaryPath()
            ?: return XrayRoutingUpdateResult.Failed("the bundled Android Xray executable is unavailable")
        val workingDirectory = File(binDir)
        if (!workingDirectory.isDirectory) {
            return XrayRoutingUpdateResult.Failed("the Xray working directory is unavailable")
        }

        var inputFile: File? = null
        var outputFile: File? = null
        return try {
            inputFile = File.createTempFile("routing-update-", ".json", workingDirectory).apply {
                writeText(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject { put("routing", routing) },
                    ),
                )
            }
            outputFile = File.createTempFile("routing-update-", ".log", workingDirectory)
            val process = ProcessBuilder(buildXrayRoutingCommand(executable, server, inputFile.absolutePath))
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .apply {
                    environment()["xray.location.asset"] = workingDirectory.absolutePath
                    environment()["XRAY_LOCATION_ASSET"] = workingDirectory.absolutePath
                }
                .start()

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                XrayRoutingUpdateResult.Failed("the Xray API command timed out")
            } else if (process.exitValue() != 0) {
                val output = outputFile.readText().trim().take(MAX_ERROR_LENGTH)
                XrayRoutingUpdateResult.Failed(output.ifEmpty { "the Xray API command failed" })
            } else {
                XrayRoutingUpdateResult.Applied
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            XrayRoutingUpdateResult.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            inputFile?.delete()
            outputFile?.delete()
        }
    }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 3L
        const val MAX_ERROR_LENGTH = 500
    }
}

internal fun buildXrayRoutingCommand(
    executable: String,
    server: String,
    inputPath: String,
): List<String> = listOf(
    executable,
    "api",
    "adrules",
    "--server=$server",
    "--timeout=$XRAY_API_TIMEOUT_SECONDS",
    inputPath,
)

private const val MILLISECONDS_PER_SECOND = 1_000L
private const val XRAY_API_TIMEOUT_SECONDS =
    (XRAY_API_TIMEOUT_MS + MILLISECONDS_PER_SECOND - 1) / MILLISECONDS_PER_SECOND
