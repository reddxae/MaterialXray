package com.material.xray.core.network

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLatencyTesterTest {

    @Test
    fun `HTTP probe returns fastest of two successful attempts`() = runTest {
        val responses = ArrayDeque(listOf(200, 204))
        var requestCount = 0
        val client = clientReturning {
            requestCount += 1
            responses.removeFirst()
        }

        val latency = measureBestHttpLatency(
            client = client,
            request = request,
            nanoTime = clock(0, 80, 100, 130),
        )

        assertEquals(30, latency)
        assertEquals(2, requestCount)
    }

    @Test
    fun `HTTP probe retries after first attempt fails`() = runTest {
        val responses = ArrayDeque(listOf(500, 204))
        val client = clientReturning { responses.removeFirst() }

        val latency = measureBestHttpLatency(
            client = client,
            request = request,
            nanoTime = clock(0, 100, 145),
        )

        assertEquals(45, latency)
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `HTTP probe consumes successful response bodies`() = runTest {
        val bodies = mutableListOf(
            "first response".toResponseBody(),
            "second response".toResponseBody(),
        )
        var responseIndex = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodies[responseIndex++])
                    .build()
            }
            .build()

        measureBestHttpLatency(
            client = client,
            request = request,
            nanoTime = clock(0, 20, 30, 40),
        )

        assertTrue(bodies.all { it.source().exhausted() })
    }

    private fun clientReturning(code: () -> Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code())
                .message("Test response")
                .body("body".toResponseBody())
                .build()
        }
        .build()

    private fun clock(vararg milliseconds: Long): () -> Long {
        val values = milliseconds.map { it * NANOS_PER_MILLISECOND }.iterator()
        return { values.next() }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val request: Request = Request.Builder().url("https://example.com/generate_204").build()
    }
}
