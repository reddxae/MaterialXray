package com.material.xray.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayLogStreamerTest {
    @Test
    fun `tail offset retains requested complete lines`() {
        withLogFile("one\ntwo\nthree\nfour\n") { file ->
            val offset = findLogTailOffset(file, maxLines = 2)

            assertEquals("three\nfour\n", file.readText().substring(offset.toInt()))
        }
    }

    @Test
    fun `tail offset counts an unterminated final line`() {
        withLogFile("one\ntwo\nthree") { file ->
            val offset = findLogTailOffset(file, maxLines = 2)

            assertEquals("two\nthree", file.readText().substring(offset.toInt()))
        }
    }

    @Test
    fun `tail offset scans across multiple blocks`() {
        val lines = (0 until 3_000).map { index -> "$index-${"x".repeat(16)}" }
        withLogFile(lines.joinToString(separator = "\n", postfix = "\n")) { file ->
            val offset = findLogTailOffset(file, maxLines = 100)
            val tail = file.readText().substring(offset.toInt()).lineSequence().filter(String::isNotEmpty).toList()

            assertEquals(lines.takeLast(100), tail)
        }
    }

    @Test
    fun `complete line reader retries an unfinished trailing line`() {
        withLogFile("one\npartial") { file ->
            val first = readCompleteLogLines(file, startOffset = 0L)
            assertEquals(listOf("one"), first.lines)
            assertEquals(4L, first.nextOffset)

            file.appendText(" line\n")
            val second = readCompleteLogLines(file, startOffset = first.nextOffset)

            assertEquals(listOf("partial line"), second.lines)
            assertEquals(file.length(), second.nextOffset)
        }
    }

    @Test
    fun `complete line reader decodes UTF-8 and strips carriage returns`() {
        withLogFile("привет\r\n") { file ->
            val result = readCompleteLogLines(file, startOffset = 0L)

            assertEquals(listOf("привет"), result.lines)
            assertEquals(file.length(), result.nextOffset)
        }
    }

    private fun withLogFile(content: String, block: (File) -> Unit) {
        val file = kotlin.io.path.createTempFile("xray-log", ".txt").toFile()
        try {
            file.writeText(content)
            block(file)
        } finally {
            file.delete()
        }
    }
}
