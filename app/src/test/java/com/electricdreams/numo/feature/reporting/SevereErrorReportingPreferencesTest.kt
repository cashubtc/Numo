package com.electricdreams.numo.feature.reporting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SevereErrorReportingPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("IssueReportingPreferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SevereErrorReportStore(context).clear()
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setExecutor(SynchronousExecutor()).build()
            )
        }
    }

    @Test
    fun `automatic severe error reporting is enabled by default`() {
        assertTrue(SevereErrorReportingPreferences.isEnabled(context))
    }

    @Test
    fun `disabling reporting clears pending report`() {
        SevereErrorReportStore(context).write(
            SevereErrorSanitizer.sanitize(RuntimeException(), 123L)
        )

        SevereErrorReportingPreferences.setEnabled(context, false)

        assertFalse(SevereErrorReportingPreferences.isEnabled(context))
        assertNull(SevereErrorReportStore(context).read())
    }
}
