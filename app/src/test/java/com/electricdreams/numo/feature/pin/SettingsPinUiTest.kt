package com.electricdreams.numo.feature.pin

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

import com.electricdreams.numo.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPinUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPinPreferences() {
        context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        PinManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun `setup requires four digits and back returns from confirmation to entry`() {
        ActivityScenario.launch(PinSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val keypad = activity.findViewById<PinKeypadView>(R.id.pin_keypad)
                val next = activity.findViewById<Button>(R.id.continue_button)
                val digit = keypad.children.filterIsInstance<Button>().first { it.text == "1" }
                repeat(3) { digit.performClick() }
                assertFalse(next.isEnabled)
                digit.performClick()
                assertTrue(next.isEnabled)
                next.performClick()
                assertEquals(activity.getString(R.string.pin_setup_confirm_title),
                    activity.findViewById<TextView>(R.id.title).text.toString())
                assertFalse(next.isEnabled)
                activity.findViewById<View>(R.id.back_button).performClick()
                assertEquals(activity.getString(R.string.pin_setup_create_title),
                    activity.findViewById<TextView>(R.id.title).text.toString())
                assertFalse(activity.isFinishing)
                assertFalse(PinManager.getInstance(activity).isPinEnabled())
            }
        }
    }

    @Test
    fun `entry keeps its caller title and opens recovery from the new layout`() {
        val intent = Intent(context, PinEntryActivity::class.java)
            .putExtra(PinEntryActivity.EXTRA_TITLE, "Authorize a withdrawal")
            .putExtra(PinEntryActivity.EXTRA_ALLOW_BACK, false)
        ActivityScenario.launch<PinEntryActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Authorize a withdrawal",
                    activity.findViewById<TextView>(R.id.title).text.toString())
                assertEquals(View.INVISIBLE,
                    activity.findViewById<View>(R.id.back_button).visibility)
                activity.findViewById<View>(R.id.forgot_pin_button).performClick()
                assertEquals(PinResetActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }
}
