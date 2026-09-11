package com.electricdreams.numo.core.dev

import android.app.Application
import android.content.Context
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import com.google.gson.Gson
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [ErrorLogStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ErrorLogStoreTest {

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        AppGlobals.init(context)

        // Clear any existing logs before each test.
        ErrorLogStore.clearAll()
    }

    @Test
    fun `appendError stores entries and getAllErrors returns them sorted`() {
        ErrorLogStore.appendError(tag = "TagB", message = "Second", timestamp = Date(2_005))
        ErrorLogStore.appendError(tag = "TagA", message = "First", timestamp = Date(2_000))

        val all: List<ErrorLogEntry> = ErrorLogStore.getAllErrors()

        assertEquals(2, all.size)
        assertEquals("First", all[0].message)
        assertEquals("Second", all[1].message)
    }

    @Test
    fun `getErrorsUpTo includes only entries at or before cutoff and keeps sort order`() {
        // First entry
        ErrorLogStore.appendError(tag = "TagA", message = "Before", timestamp = Date(2_000))
        // Second entry
        ErrorLogStore.appendError(tag = "TagB", message = "After", timestamp = Date(2_005))

        val all = ErrorLogStore.getAllErrors()
        val first = all.first().timestamp
        val last = all.last().timestamp

        // Cutoff at first timestamp should at least include the first entry and keep ordering
        val upToFirst = ErrorLogStore.getErrorsUpTo(first)
        assertTrue(upToFirst.isNotEmpty())
        assertEquals("Before", upToFirst.first().message)
        assertEquals(1, upToFirst.size)

        // Cutoff at last timestamp should include all entries, still sorted
        val upToLast = ErrorLogStore.getErrorsUpTo(last)
        assertEquals(2, upToLast.size)
        assertEquals("Before", upToLast[0].message)
        assertEquals("After", upToLast[1].message)
    }

    @Test
    fun `clearAll removes all stored entries`() {
        ErrorLogStore.appendError(tag = "Tag", message = "One")
        ErrorLogStore.appendError(tag = "Tag", message = "Two")

        ErrorLogStore.clearAll()

        val all = ErrorLogStore.getAllErrors()
        assertTrue(all.isEmpty())
    }

    @Test
    fun `timestamp round trip preserves milliseconds`() {
        ErrorLogStore.appendError("Tag", "Old error", timestamp = Date(1_767_225_600_123))
        assertEquals(1_767_225_600_123, ErrorLogStore.getAllErrors().single().timestamp.time)
    }

    @Test
    fun `malformed history is repaired and subsequent errors can be saved`() {
        for (json in listOf("{broken", "null", "{}", "[null, {}, {\"id\":null}]")) {
            preferences().edit().putString("logs", json).commit()
            assertTrue(ErrorLogStore.getAllErrors().isEmpty())
            ErrorLogStore.appendError("Tag", "Recovered")
            assertEquals("Recovered", ErrorLogStore.getAllErrors().single().message)
        }
    }

    @Test
    fun `legacy dates remain readable and malformed individual records are discarded`() {
        val entry = ErrorLogEntry("legacy", Date(1_700_000_000_000), "Tag", "Keep me")
        val json = "[" + Gson().toJson(entry) + ",{\"id\":\"broken\"}]"
        preferences().edit().putString("logs", json).commit()
        assertEquals(listOf(entry), ErrorLogStore.getAllErrors())
        assertEquals(listOf(entry), ErrorLogStore.getAllErrors())
    }

    @Test
    fun `replayed records and additional stack chunks do not create duplicate rows`() {
        val entry = ErrorLogEntry("same", Date(1_000), "Tag", "Failure")
        ErrorLogStore.record(entry)
        ErrorLogStore.record(entry)
        ErrorLogStore.record(entry.copy(stackTrace = "java.io.IOException: offline"))
        assertEquals(1, ErrorLogStore.getAllErrors().size)
        assertEquals("java.io.IOException: offline", ErrorLogStore.getAllErrors().single().stackTrace)
    }

    @Test
    fun `direct throwable storage keeps only the exception and five frames`() {
        val error = IllegalStateException("Failure").apply {
            stackTrace = (1..30).map {
                StackTraceElement("Mint", "step$it", "Mint.kt", it)
            }.toTypedArray()
        }
        ErrorLogStore.appendError("Tag", "Failed", error)
        val stack = ErrorLogStore.getAllErrors().single().stackTrace.orEmpty()
        assertTrue(stack.contains("Mint.step5(Mint.kt:5)"))
        assertFalse(stack.contains("Mint.step6("))
        assertEquals(7, stack.lines().size)
    }

    @Test
    fun `history retains the newest 500 errors when older records are replayed`() {
        val entries = (1..500).map { ErrorLogEntry("$it", Date(it.toLong()), "Tag", "$it") }
        for (entry in entries) ErrorLogStore.record(entry)
        ErrorLogStore.record(ErrorLogEntry("old", Date(0), "Tag", "Old"))
        ErrorLogStore.record(ErrorLogEntry("new", Date(501), "Tag", "New"))
        val all = ErrorLogStore.getAllErrors()
        assertEquals(500, all.size)
        assertEquals("2", all.first().id)
        assertEquals("new", all.last().id)
    }

    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("DeveloperErrorLogs", Context.MODE_PRIVATE)
}
