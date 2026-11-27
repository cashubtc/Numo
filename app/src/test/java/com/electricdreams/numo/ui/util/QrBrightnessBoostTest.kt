package com.electricdreams.numo.ui.util

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.feature.settings.ThemeSettingsActivity
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QrBrightnessBoostTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        QrBrightnessBoost.setEnabled(app, true)
    }

    @Test
    fun `boost is on by default`() {
        PreferenceStore.app(app).remove(QrBrightnessBoost.PREF_KEY)
        assertTrue(QrBrightnessBoost.isEnabled(app))
    }

    @Test
    fun `given boost enabled, when screen resumes, then window goes to full brightness`() {
        launch().use { (controller, _) ->
            assertEquals(FULL, brightness(controller), 0f)
        }
    }

    @Test
    fun `given boosted screen, when it pauses or finishes, then brightness is handed back`() {
        val (controller, boost) = launch()
        controller.pause()
        assertEquals(NONE, brightness(controller), 0f)
        assertFalse(boost.isBoosting)

        controller.resume()
        assertEquals(FULL, brightness(controller), 0f)

        controller.pause().stop().destroy()
        assertEquals(NONE, brightness(controller), 0f)
    }

    @Test
    fun `given boost disabled in settings, when screen resumes, then brightness is untouched`() {
        QrBrightnessBoost.setEnabled(app, false)
        launch().use { (controller, boost) ->
            assertEquals(NONE, brightness(controller), 0f)
            assertFalse(boost.isBoosting)
        }
    }

    @Test
    fun `when the QR is covered, then brightness drops until it is shown again`() {
        launch().use { (controller, boost) ->
            boost.setQrVisible(false)
            assertEquals(NONE, brightness(controller), 0f)

            boost.setQrVisible(true)
            assertEquals(FULL, brightness(controller), 0f)
        }
    }

    @Test
    fun `when toggled on screen, then the preference and brightness change together`() {
        launch().use { (controller, boost) ->
            assertFalse(boost.toggle())
            assertFalse(QrBrightnessBoost.isEnabled(app))
            assertEquals(NONE, brightness(controller), 0f)

            assertTrue(boost.toggle())
            assertTrue(QrBrightnessBoost.isEnabled(app))
            assertEquals(FULL, brightness(controller), 0f)
        }
    }

    @Test
    fun `theme settings switch reflects and saves the preference`() {
        QrBrightnessBoost.setEnabled(app, false)
        Robolectric.buildActivity(ThemeSettingsActivity::class.java).setup().use { controller ->
            val switch = controller.get().findViewById<MaterialSwitch>(R.id.qr_brightness_switch)
            assertFalse(switch.isChecked)

            switch.performClick()

            assertTrue(switch.isChecked)
            assertTrue(QrBrightnessBoost.isEnabled(app))
        }
    }

    private fun launch(): Launched {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val boost = QrBrightnessBoost(controller.get())
        controller.setup()
        return Launched(controller, boost)
    }

    private fun brightness(controller: ActivityController<ComponentActivity>): Float =
        controller.get().window.attributes.screenBrightness

    private data class Launched(
        val controller: ActivityController<ComponentActivity>,
        val boost: QrBrightnessBoost,
    ) : AutoCloseable {
        override fun close() = controller.close()
    }

    private companion object {
        const val FULL = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        const val NONE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}
