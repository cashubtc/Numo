package com.electricdreams.numo.ui.components

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import com.electricdreams.numo.R
import com.google.android.material.shape.MaterialShapeDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UnitPickerDialogTest {
    private var previousNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

    @Before
    fun useConfiguredNightMode() {
        previousNightMode = AppCompatDelegate.getDefaultNightMode()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun restoreNightMode() {
        AppCompatDelegate.setDefaultNightMode(previousNightMode)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `long unit and issuer labels wrap without truncation at larger text sizes`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        val label = "25,000 MERCHANT_POINTS · merchant-loyalty-rewards.example"
        val dialog = UnitPickerDialog.show(
            activity, R.string.pos_charge_unit_dialog_title, listOf(label), 0,
        ) {}
        try {
            val row = dialog.listView.adapter.getView(0, null, dialog.listView) as TextView
            row.textSize = 24f
            val width = (280 * activity.resources.displayMetrics.density).toInt()
            row.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            row.layout(0, 0, width, row.measuredHeight)
            assertTrue(row.lineCount > 1)
            assertEquals(label.length, row.layout.getLineEnd(row.lineCount - 1))
            assertTrue((0 until row.lineCount).all { row.layout.getEllipsisCount(it) == 0 })
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `light picker has an opaque surface readable choices and current selection`() {
        verifyPicker()
    }

    @Test
    @Config(qualifiers = "night")
    fun `dark picker has an opaque surface readable choices and current selection`() {
        verifyPicker()
    }

    private fun verifyPicker() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        var selected = -1
        val dialog = UnitPickerDialog.show(
            activity, R.string.pos_charge_unit_dialog_title, listOf("sat", "USD"), 1,
            description = R.string.unit_picker_mints_description,
        ) { selected = it }
        try {
            // A transparent custom-dialog theme used to leave the list over the dimmed POS.
            val background = requireNotNull(dialog.window).decorView.background as InsetDrawable
            val surface = background.drawable as MaterialShapeDrawable
            val surfaceColor = requireNotNull(surface.fillColor).defaultColor
            assertEquals(255, Color.alpha(surfaceColor))
            val row = dialog.listView.adapter.getView(0, null, dialog.listView) as TextView
            assertTrue(
                "Choice color ${row.currentTextColor} must contrast with surface $surfaceColor",
                ColorUtils.calculateContrast(row.currentTextColor, surfaceColor) >= 4.5,
            )
            val title = dialog.findViewById<TextView>(R.id.unit_picker_title)
            assertTrue(ColorUtils.calculateContrast(requireNotNull(title).currentTextColor, surfaceColor) >= 4.5)
            val description = requireNotNull(
                dialog.findViewById<TextView>(R.id.unit_picker_description),
            )
            assertEquals(activity.getString(R.string.unit_picker_mints_description), description.text)
            assertTrue(description.isShown)
            assertTrue(ColorUtils.calculateContrast(description.currentTextColor, surfaceColor) >= 4.5)
            assertTrue(dialog.listView.isShown)
            assertEquals(2, dialog.listView.adapter.count)
            assertEquals(1, dialog.listView.checkedItemPosition)
            dialog.listView.performItemClick(row, 0, 0)
            assertEquals(0, selected)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `cancel preserves the selected unit`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        var selected = 1
        val dialog = UnitPickerDialog.show(
            activity, R.string.pos_charge_unit_dialog_title, listOf("sat", "USD"), selected,
        ) { selected = it }
        try {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(1, selected)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
