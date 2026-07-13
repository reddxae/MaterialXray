package com.material.xray.ui.home

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QrLuminanceBuffersTest {

    @Test
    fun `copies packed luminance rows without padding`() {
        val buffers = QrLuminanceBuffers()
        val source = byteArrayOf(
            1,
            2,
            3,
            99,
            4,
            5,
            6,
            99,
        )

        val luminance = buffers.copyLuminance(
            source = ByteBuffer.wrap(source),
            width = 3,
            height = 2,
            rowStride = 4,
            pixelStride = 1,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), luminance)
    }

    @Test
    fun `extracts luminance from interleaved pixels`() {
        val buffers = QrLuminanceBuffers()
        val source = byteArrayOf(
            10, 90, 20, 90, 30, 90, 90, 90,
            40, 90, 50, 90, 60,
        )

        val luminance = buffers.copyLuminance(
            source = ByteBuffer.wrap(source),
            width = 3,
            height = 2,
            rowStride = 8,
            pixelStride = 2,
        )

        assertArrayEquals(byteArrayOf(10, 20, 30, 40, 50, 60), luminance)
    }

    @Test
    fun `reuses luminance and inversion arrays for equal frame sizes`() {
        val buffers = QrLuminanceBuffers()
        val first = buffers.copyLuminance(ByteBuffer.wrap(byteArrayOf(0, 64, 127, -1)), 2, 2, 2, 1)
        val firstInverted = buffers.invert(first)
        val second = buffers.copyLuminance(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, 2, 2, 1)
        val secondInverted = buffers.invert(second)

        assertSame(first, second)
        assertSame(firstInverted, secondInverted)
        assertArrayEquals(byteArrayOf(-2, -3, -4, -5), secondInverted)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), second)
    }
}
