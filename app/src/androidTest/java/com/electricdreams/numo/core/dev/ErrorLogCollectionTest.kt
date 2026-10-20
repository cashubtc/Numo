package com.electricdreams.numo.core.dev

import android.content.Context
import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.settings.DeveloperPrefs
import com.electricdreams.numo.feature.settings.ErrorLogsActivity
import com.electricdreams.numo.feature.settings.ErrorLogsAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorLogCollectionTest {
    @Test
    fun realLogcatKeepsOneShortExceptionAndRefreshesTheScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previouslyEnabled = DeveloperPrefs.isDeveloperModeEnabled(context)
        val tag = "ErrorLogTest-${System.nanoTime()}"
        try {
            DeveloperPrefs.setDeveloperModeEnabled(context, true)
            ActivityScenario.launch(ErrorLogsActivity::class.java).use { scenario ->
                val error = IllegalStateException("Synthetic collection test").apply {
                    stackTrace = (1..150).map {
                        StackTraceElement("com.example.Mint", "step$it", "Mint.kt", it)
                    }.toTypedArray()
                }
                val before = System.currentTimeMillis()
                Log.e(tag, "Synthetic request failed", error)
                val deadline = System.nanoTime() + 10_000_000_000L
                var visible = false
                do {
                    scenario.onActivity { activity ->
                        val adapter = activity.findViewById<RecyclerView>(
                            R.id.error_logs_recycler_view).adapter as ErrorLogsAdapter
                        visible = adapter.currentList.any {
                            it.tag == tag && it.stackTrace.orEmpty().contains("stack trace truncated")
                        }
                    }
                    if (visible) break
                    Thread.sleep(50)
                } while (System.nanoTime() < deadline)
                assertTrue("Log.e must reach the in-app screen through the real collector", visible)
                // Allow all chunks of this large exception to arrive before checking row count.
                Thread.sleep(500)
                val entries = ErrorLogStore.getAllErrors().filter { it.tag == tag }
                assertEquals(1, entries.size)
                val entry = entries.single()
                assertEquals("Synthetic request failed", entry.message)
                assertTrue(entry.timestamp.time in before..System.currentTimeMillis())
                assertTrue(entry.stackTrace.orEmpty().contains("Mint.step5(Mint.kt:5)"))
                assertFalse(entry.stackTrace.orEmpty().contains("Mint.step6("))
            }
        } finally {
            DeveloperPrefs.setDeveloperModeEnabled(context, previouslyEnabled)
        }
    }
}
