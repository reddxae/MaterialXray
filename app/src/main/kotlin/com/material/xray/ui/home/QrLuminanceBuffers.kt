package com.material.xray.ui.home

import java.nio.ByteBuffer

internal class QrLuminanceBuffers {
    private var luminance = ByteArray(0)
    private var row = ByteArray(0)
    private var inverted = ByteArray(0)

    fun copyLuminance(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        require(width > 0 && height > 0)
        require(rowStride > 0 && pixelStride > 0)
        val outputSize = Math.multiplyExact(width, height)
        val requiredRowLength = Math.addExact(Math.multiplyExact(width - 1, pixelStride), 1)
        require(rowStride >= requiredRowLength)
        if (luminance.size != outputSize) luminance = ByteArray(outputSize)
        var outputOffset = 0

        for (rowIndex in 0 until height) {
            val rowStart = Math.multiplyExact(rowIndex, rowStride)
            require(rowStart <= source.limit() - requiredRowLength)
            source.position(rowStart)
            if (pixelStride == 1) {
                source.get(luminance, outputOffset, width)
                outputOffset += width
            } else {
                val rowLength = source.remaining().coerceAtMost(rowStride)
                if (row.size < rowLength) row = ByteArray(rowLength)
                source.get(row, 0, rowLength)
                for (column in 0 until width) {
                    luminance[outputOffset++] = row[column * pixelStride]
                }
            }
        }
        return luminance
    }

    fun invert(source: ByteArray): ByteArray {
        if (inverted.size != source.size) inverted = ByteArray(source.size)
        for (index in source.indices) {
            inverted[index] = (255 - (source[index].toInt() and 0xFF)).toByte()
        }
        return inverted
    }
}
