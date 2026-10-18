package com.electricdreams.numo.feature.items

import android.os.Build
import androidx.core.view.WindowCompat
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ItemSelectionSystemBarsTest {
    @Test
    @Config(qualifiers = "night")
    fun `populated checkout uses light system icons in dark mode`() {
        checkSystemBars(empty = false, lightIcons = true)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `populated checkout uses dark system icons in light mode`() {
        checkSystemBars(empty = false, lightIcons = false)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `empty checkout keeps light icons on its dark artwork in light mode`() {
        checkSystemBars(empty = true, lightIcons = true)
    }

    @Test
    @Config(qualifiers = "night")
    fun `empty checkout keeps light icons in dark mode`() {
        checkSystemBars(empty = true, lightIcons = true)
    }

    private fun checkSystemBars(empty: Boolean, lightIcons: Boolean) {
        val activity = Robolectric.buildActivity(ItemSelectionActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setNavigationBarStyle",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, empty))
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (lightIcons) {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        } else {
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
        val background = activity.getColor(
            if (empty) R.color.empty_state_background else R.color.color_bg_white)
        // Android 15 enforces transparent system bars for this target SDK.
        if (Build.VERSION.SDK_INT < 35) {
            assertEquals(background, activity.window.statusBarColor)
            assertEquals(background, activity.window.navigationBarColor)
        }
    }
}
