package com.electricdreams.numo.feature.settings

import android.app.Application
import android.app.DatePickerDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.R
import com.electricdreams.numo.core.dev.ErrorLogStore
import java.util.Calendar
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ErrorLogsActivityTest {
    @Before
    fun setUp() {
        AppGlobals.init(RuntimeEnvironment.getApplication())
        DeveloperPrefs.setDeveloperModeEnabled(RuntimeEnvironment.getApplication(), false)
        ErrorLogStore.clearAll()
    }

    @Test
    fun `new errors appear without reopening and today includes errors after screen creation`() {
        ActivityScenario.launch(ErrorLogsActivity::class.java).use { scenario ->
            awaitRows(scenario, 0)
            val lateToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }.time
            ErrorLogStore.appendError("Test", "Late error", timestamp = lateToday)
            awaitRows(scenario, 1)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.empty_view).visibility)
                activity.findViewById<View>(R.id.copy_all_button).performClick()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertTrue(clipboard.primaryClip?.getItemAt(0)?.text.toString().contains("Late error"))
            }
            ErrorLogStore.clearAll()
            awaitRows(scenario, 0)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.copy_all_button).isEnabled)
                assertFalse(activity.findViewById<View>(R.id.share_button).isEnabled)
            }
        }
    }

    @Test
    fun `errors arriving in the background are loaded on resume`() {
        ActivityScenario.launch(ErrorLogsActivity::class.java).use { scenario ->
            awaitRows(scenario, 0)
            scenario.moveToState(Lifecycle.State.CREATED)
            ErrorLogStore.appendError("Test", "Background error")
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitRows(scenario, 1)
        }
    }

    @Test
    fun `selected date stays inclusive and survives recreation while history refreshes`() {
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        ErrorLogStore.appendError("Test", "Yesterday", timestamp = yesterday.time)
        ErrorLogStore.appendError("Test", "Today", timestamp = Date())
        ActivityScenario.launch(ErrorLogsActivity::class.java).use { scenario ->
            awaitRows(scenario, 2)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.date_filter_row).performClick()
                val dialog = ShadowDialog.getLatestDialog() as DatePickerDialog
                dialog.datePicker.updateDate(yesterday.get(Calendar.YEAR),
                    yesterday.get(Calendar.MONTH), yesterday.get(Calendar.DAY_OF_MONTH))
                dialog.getButton(DatePickerDialog.BUTTON_POSITIVE).performClick()
            }
            awaitRows(scenario, 1)
            scenario.recreate()
            awaitRows(scenario, 1)
            ErrorLogStore.appendError("Test", "More today")
            scenario.onActivity { activity ->
                assertEquals("Yesterday", adapter(activity).currentList.single().message)
                assertTrue(activity.findViewById<TextView>(R.id.date_filter_value).text != "Today")
            }
        }
    }

    @Test
    fun `corrupted history does not crash the screen and later errors appear`() {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "DeveloperErrorLogs", Context.MODE_PRIVATE
        ).edit().putString("logs", "{broken").commit()
        ActivityScenario.launch(ErrorLogsActivity::class.java).use { scenario ->
            awaitRows(scenario, 0)
            ErrorLogStore.appendError("Test", "Recovered")
            awaitRows(scenario, 1)
        }
    }

    private fun adapter(activity: ErrorLogsActivity) =
        activity.findViewById<RecyclerView>(R.id.error_logs_recycler_view).adapter as ErrorLogsAdapter

    private fun awaitRows(scenario: ActivityScenario<ErrorLogsActivity>, count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var actual = -1
        do {
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { actual = adapter(it).itemCount }
            if (actual == count) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertEquals(count, actual)
    }
}
