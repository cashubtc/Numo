package com.electricdreams.numo.feature.reporting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SevereErrorReportWorkerTest {
    private lateinit var context: Context
    private lateinit var store: SevereErrorReportStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SevereErrorReportStore(context)
        store.clear()
        SevereErrorReportingPreferences.setEnabled(context, true)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `reports older than seven days expire`() {
        assertEquals(
            true,
            SevereErrorReportRetention.isExpired(
                occurredAtEpochMillis = TimeUnit.DAYS.toMillis(1),
                nowMillis = TimeUnit.DAYS.toMillis(9)
            )
        )
    }

    @Test
    fun `worker retains fresh report when relay does not accept it`() = runBlocking {
        store.write(
            PendingSevereErrorReport(
                localToken = "worker-test-token",
                occurredAtEpochMillis = Long.MAX_VALUE,
                exceptionTypes = listOf("RuntimeException"),
                appFrames = emptyList()
            )
        )
        assertNotNull(store.read())
        val worker = TestListenableWorkerBuilder<SevereErrorReportWorker>(context).build()
        worker.reportPublisher = IssueReportPublisher { _, _, _, _ -> false }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNotNull(store.read())
    }
}
