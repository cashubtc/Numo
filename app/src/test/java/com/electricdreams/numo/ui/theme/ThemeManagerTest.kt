package com.electricdreams.numo.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                val charge = activity.findViewById<TextView>(R.id.submit_button)
                assertEquals(charge.textSize, selector.textSize)
                assertEquals(charge.typeface, selector.typeface)
                assertNull(selector.backgroundTintList)
                listOf(true to false, true to true, false to false).forEach { (enabled, pressed) ->
                    charge.isEnabled = enabled
                    selector.isEnabled = enabled
                    charge.isPressed = pressed
                    selector.isPressed = pressed
                    assertEquals(charge.currentTextColor, selector.currentTextColor)
                    assertEquals(
                        selector.currentTextColor,
                        TextViewCompat.getCompoundDrawableTintList(selector)?.getColorForState(
                            selector.drawableState, 0,
                        ),
                    )
                    assertTrue("$theme enabled=$enabled pressed=$pressed backgrounds must match",
                        render(charge.background).sameAs(render(selector.background)))
                }
                selector.isEnabled = true
                assertTrue("The mint must remain selectable when Charge is disabled", selector.isEnabled)
            }
        } finally {
            prefs.remove("app_theme")
            controller.pause().stop().destroy()
        }
    }

    private fun render(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.jumpToCurrentState()
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
