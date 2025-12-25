package com.electricdreams.numo.core.dev

import android.content.Context
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Calendar

/**
 * Unit tests for [ErrorLogStore].
 */
@RunWith(RobolectricTestRunner::class)
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
        ErrorLogStore.appendError(tag = "TagA", message = "First")
        Thread.sleep(5)
        ErrorLogStore.appendError(tag = "TagB", message = "Second")

        val all: List<ErrorLogEntry> = ErrorLogStore.getAllErrors()

        assertEquals(2, all.size)
        assertEquals("First", all[0].message)
        assertEquals("Second", all[1].message)
    }

    @Test
    fun `getErrorsUpTo includes only entries at or before cutoff and keeps sort order`() {
        // First entry
        ErrorLogStore.appendError(tag = "TagA", message = "Before")
        Thread.sleep(5)
        // Second entry
        ErrorLogStore.appendError(tag = "TagB", message = "After")

        val all = ErrorLogStore.getAllErrors()
        val first = all.first().timestamp
        val last = all.last().timestamp

        // Cutoff at first timestamp should at least include the first entry and keep ordering
        val upToFirst = ErrorLogStore.getErrorsUpTo(first)
        assertTrue(upToFirst.isNotEmpty())
        assertEquals("Before", upToFirst.first().message)

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
}
