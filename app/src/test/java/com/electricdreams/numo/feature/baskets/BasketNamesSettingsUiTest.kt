package com.electricdreams.numo.feature.baskets

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.electricdreams.numo.R
import com.electricdreams.numo.ui.components.InputBottomSheet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BasketNamesSettingsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = BasketNamesManager.getInstance(context)

    @Before
    fun clearNames() {
        manager.clearAll()
    }

    @Test
    fun `shared input sheet adds and edits a preset name`() {
        ActivityScenario.launch(BasketNamesSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.empty_state_action).performClick()
                val add = inputSheet(activity)
                add.findViewById<EditText>(R.id.dialog_input).setText(" Table 1 ")
                add.findViewById<View>(R.id.save_button).performClick()
                activity.supportFragmentManager.executePendingTransactions()
                assertEquals(listOf("Table 1"), manager.getPresetNames())

                activity.findViewById<LinearLayout>(R.id.names_list).getChildAt(0).performClick()
                val edit = inputSheet(activity)
                assertEquals("Table 1",
                    edit.findViewById<EditText>(R.id.dialog_input).text.toString())
                edit.findViewById<EditText>(R.id.dialog_input).setText("Table 2")
                edit.findViewById<View>(R.id.save_button).performClick()
                assertEquals(listOf("Table 2"), manager.getPresetNames())
            }
        }
    }

    @Test
    fun `blank and duplicate names keep the input sheet open without changing presets`() {
        manager.addPresetName("Table 1")
        ActivityScenario.launch(BasketNamesSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.add_name_button).performClick()
                val sheet = inputSheet(activity)
                val input = sheet.findViewById<EditText>(R.id.dialog_input)
                val save = sheet.findViewById<View>(R.id.save_button)
                for (invalid in listOf(" ", "table 1")) {
                    input.setText(invalid)
                    save.performClick()
                    activity.supportFragmentManager.executePendingTransactions()
                    assertEquals(listOf("Table 1"), manager.getPresetNames())
                    assertTrue(activity.supportFragmentManager.fragments
                        .filterIsInstance<InputBottomSheet>().single().dialog?.isShowing == true)
                }
            }
        }
    }

    @Test
    fun `input and save remain visible when the keyboard reduces the sheet viewport`() {
        ActivityScenario.launch(BasketNamesSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.empty_state_action).performClick()
                val sheet = inputSheet(activity) as ViewGroup
                val density = activity.resources.displayMetrics.density
                ViewCompat.dispatchApplyWindowInsets(sheet, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(),
                        Insets.of(0, 0, 0, (300 * density).toInt()))
                    .build())
                sheet.measure(
                    View.MeasureSpec.makeMeasureSpec((360 * density).toInt(),
                        View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((450 * density).toInt(),
                        View.MeasureSpec.AT_MOST),
                )
                sheet.layout(0, 0, sheet.measuredWidth, sheet.measuredHeight)
                for (id in listOf(R.id.dialog_input, R.id.save_button)) {
                    val control = sheet.findViewById<View>(id)
                    val bounds = Rect(0, 0, control.width, control.height)
                    sheet.offsetDescendantRectToMyCoords(control, bounds)
                    assertTrue(bounds.top >= sheet.paddingTop)
                    assertTrue(bounds.bottom <= sheet.height - sheet.paddingBottom)
                }
            }
        }
    }

    private fun inputSheet(activity: BasketNamesSettingsActivity): View {
        activity.supportFragmentManager.executePendingTransactions()
        return requireNotNull(activity.supportFragmentManager.fragments
            .filterIsInstance<InputBottomSheet>().single().view)
    }
}
