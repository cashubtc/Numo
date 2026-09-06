package com.electricdreams.numo.ui.theme

import android.app.Application
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ThemeManagerTest {
    @Test
    fun `unit selector follows every POS theme`() = verifyThemes()

    @Test
    @Config(qualifiers = "night")
    fun `unit selector follows every POS theme in dark mode`() = verifyThemes()

    private fun verifyThemes() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        controller.setup()
        activity.setContentView(R.layout.activity_modern_pos)
        val prefs = PreferenceStore.app(activity)
        try {
            listOf("white", "green", "obsidian", "bitcoin_orange").forEach { theme ->
                prefs.putString("app_theme", theme)
                val amount = activity.findViewById<TextView>(R.id.amount_display)
                ThemeManager(activity).applyTheme(
                    amount,
                    activity.findViewById(R.id.secondary_amount_display),
                    activity.findViewById(R.id.error_message),
                    activity.findViewById(R.id.currency_switch_button),
                    activity.findViewById(R.id.submit_button),
                )
                val selector = activity.findViewById<TextView>(R.id.charge_unit_selector)
                assertEquals(amount.currentTextColor, selector.currentTextColor)
                assertEquals(
                    selector.currentTextColor,
                    TextViewCompat.getCompoundDrawableTintList(selector)?.defaultColor,
                )
                val background = ThemeManager.resolveBackgroundColor(activity)
                val surface = if (theme == "white") background else {
                    ColorUtils.compositeColors(activity.getColor(R.color.color_black_40), background)
                }
                assertTrue("$theme selector must be readable", ColorUtils.calculateContrast(
                    selector.currentTextColor, surface,
                ) >= 4.5)
            }
        } finally {
            prefs.remove("app_theme")
            controller.pause().stop().destroy()
        }
    }
}
