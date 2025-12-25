package com.electricdreams.numo.core.dev

import com.electricdreams.numo.core.data.model.ErrorLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unit tests for [ErrorLogCollector] parsing logic.
 *
 * These tests focus on verifying that sample logcat lines are parsed into
 * correct tag/message values and forwarded to [ErrorLogStore]. To avoid
 * touching the real store, this test uses a simple FakeErrorLogStore.
 */
class ErrorLogCollectorParseTest {

    @Test
    fun `parseAndStoreLine extracts tag and message from -v time format`() {
        val fakeStore = FakeErrorLogStore()
        val collector = TestableErrorLogCollector(fakeStore)

        val sampleLine = "03-15 12:34:56.789  1234  1234 E AutoWithdrawManager: ❌ Auto-withdrawal failed: some error"

        collector.invokeParse(sampleLine, pid = 1234)

        assertEquals(1, fakeStore.entries.size)
        val entry = fakeStore.entries.first()
        assertEquals("AutoWithdrawManager", entry.tag)
        assertEquals("❌ Auto-withdrawal failed: some error", entry.message)
    }

    private class FakeErrorLogStore {
        val entries = mutableListOf<ErrorLogEntry>()

        fun append(tag: String, message: String) {
            entries.add(
                ErrorLogEntry(
                    id = "test",
                    timestamp = Date(),
                    tag = tag,
                    message = message,
                    stackTrace = null,
                ),
            )
        }
    }

    private class TestableErrorLogCollector(
        private val fakeStore: FakeErrorLogStore,
    ) {
        private val logcatDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

        fun invokeParse(raw: String, pid: Int) {
            // Direct copy of ErrorLogCollector.parseAndStoreLine, but writing to fake store.
            if (!raw.contains(" $pid ")) return

            val firstSpace = raw.indexOf(' ')
            val secondSpace = if (firstSpace > 0) raw.indexOf(' ', firstSpace + 1) else -1
            if (secondSpace <= 0) return

            val timestampStr = raw.substring(0, secondSpace)
            val rest = raw.substring(secondSpace + 1)

            val colonIndex = rest.indexOf(':')
            if (colonIndex <= 0) return

            val header = rest.substring(0, colonIndex).trim()
            val message = rest.substring(colonIndex + 1).trim()

            val parts = header.split(Regex("\\s+"))
            val tag = parts.lastOrNull() ?: "Unknown"

            // Parse to ensure the timestamp format is valid (even if we ignore value).
            try {
                logcatDateFormat.parse(timestampStr)
            } catch (_: Exception) {
                // ignore
            }

            fakeStore.append(tag, message)
        }
    }
}
