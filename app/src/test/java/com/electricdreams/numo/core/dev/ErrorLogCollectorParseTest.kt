package com.electricdreams.numo.core.dev

import com.electricdreams.numo.core.data.model.ErrorLogEntry
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogCollectorParseTest {
    @Test
    fun `real binary record preserves tag unicode multiline message and timestamp`() {
        val message = "❌ Failed: timeout\n\nRequest details: mint unavailable"
        val reader = reader(logcatBytes(message, tag = "Mint: withdrawal worker"))
        val record = requireNotNull(reader.read())
        assertEquals("Mint: withdrawal worker", record.tag)
        assertEquals(message, record.message)
        assertEquals(1_767_225_600_123L, record.timestamp.time)
        assertEquals(1235, record.threadId)
        assertNull(reader.read())
    }

    @Test
    fun `reader accepts both 24 and 28 byte Android headers`() {
        for (size in listOf(24, 28)) {
            assertEquals("Failure", reader(logcatBytes("Failure", headerSize = size)).read()?.message)
        }
    }

    @Test
    fun `only errors and fatal records from this process and text buffers are collected`() {
        val stream = logcatBytes("Other app", pid = 9000) + logcatBytes("Warning", priority = 5) +
            logcatBytes("Event buffer", buffer = 2) + logcatBytes("Error") +
            logcatBytes("Fatal", priority = 7, buffer = 4)
        val reader = reader(stream)
        assertEquals("Error", reader.read()?.message)
        assertEquals("Fatal", reader.read()?.message)
        assertNull(reader.read())
    }

    @Test
    fun `truncated records and unexpected subprocess text report a read failure`() {
        assertThrows(IOException::class.java) { reader(logcatBytes("Error").dropLast(2).toByteArray()).read() }
        assertThrows(IOException::class.java) { reader("logcat: Permission denied".toByteArray()).read() }
    }

    @Test
    fun `replayed records retain identity and distinct nanosecond timestamps remain distinct`() {
        val first = reader(logcatBytes("Failure")).read()
        assertEquals(first, reader(logcatBytes("Failure")).read())
        assertNotEquals(first?.id, reader(logcatBytes("Failure", nanos = 123_456_790)).read()?.id)
    }

    @Test
    fun `one exception produces one entry containing only its top five frames`() {
        val entries = collect(logcatBytes("Request failed\njava.io.IOException: offline\n" + frames(1..20)))
        val entry = entries.single()
        assertEquals("Request failed", entry.message)
        assertTrue(entry.stackTrace.orEmpty().startsWith("java.io.IOException: offline"))
        assertTrue(entry.stackTrace.orEmpty().contains("Mint.step5(Mint.kt:5)"))
        assertFalse(entry.stackTrace.orEmpty().contains("Mint.step6("))
        assertTrue(entry.stackTrace.orEmpty().endsWith("… (stack trace truncated)"))
    }

    @Test
    fun `large stack trace split across log records updates its original entry`() {
        val entries = collect(
            logcatBytes("Request failed\njava.io.IOException: offline\n" + frames(1..3)) +
                logcatBytes(frames(4..30), nanos = 124_000_000),
        )
        val entry = entries.single()
        assertEquals("Request failed", entry.message)
        assertTrue(entry.stackTrace.orEmpty().contains("Mint.step5(Mint.kt:5)"))
        assertFalse(entry.stackTrace.orEmpty().contains("Mint.step6("))
    }

    @Test
    fun `interleaved errors from another thread do not absorb the exception tail`() {
        val entries = collect(
            logcatBytes("First\njava.io.IOException: offline\n" + frames(1..3)) +
                logcatBytes("Second", tid = 99) + logcatBytes(frames(4..30)),
        )
        assertEquals(listOf("First", "Second"), entries.map { it.message })
        assertNull(entries.last().stackTrace)
    }

    @Test
    fun `independent errors sharing a tag and timestamp remain separate`() {
        val entries = collect(logcatBytes("First") + logcatBytes("Second"))
        assertEquals(listOf("First", "Second"), entries.map { it.message })
    }

    private fun reader(bytes: ByteArray) = LogcatRecordReader(ByteArrayInputStream(bytes), 1234)

    private fun collect(bytes: ByteArray): List<ErrorLogEntry> {
        val entries = linkedMapOf<String, ErrorLogEntry>()
        val assembler = ErrorLogAssembler { entries[it.id] = it }
        val reader = reader(bytes)
        while (true) assembler.accept(reader.read() ?: break)
        return entries.values.toList()
    }

    private fun frames(range: IntRange) = range.joinToString("\n") {
        "\tat com.example.Mint.step$it(Mint.kt:$it)"
    }
}
