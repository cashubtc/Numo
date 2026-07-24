package com.electricdreams.numo.feature.reporting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SevereErrorReportStoreTest {
    private lateinit var context: Context
    private lateinit var store: SevereErrorReportStore
    private val report = PendingSevereErrorReport(
        localToken = "local-only-token",
        occurredAtEpochMillis = 123L,
        exceptionTypes = listOf("IllegalStateException"),
        appFrames = listOf(
            SanitizedStackFrame("com.electricdreams.numo.Screen", "render", 10)
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SevereErrorReportStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `write and read preserve sanitized report in no-backup storage`() {
        assertTrue(store.write(report))

        assertEquals(report, store.read())
        assertTrue(context.noBackupFilesDir.resolve("pending_severe_error.json").exists())
    }

    @Test
    fun `clear with different token preserves newer report`() {
        store.write(report)

        store.clearIfToken("different-token")

        assertEquals(report, store.read())
    }

    @Test
    fun `clear with matching token removes report`() {
        store.write(report)

        store.clearIfToken(report.localToken)

        assertNull(store.read())
    }
}
