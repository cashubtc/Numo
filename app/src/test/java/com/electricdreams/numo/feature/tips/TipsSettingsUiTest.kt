package com.electricdreams.numo.feature.tips

import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.electricdreams.numo.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TipsSettingsUiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetTips() {
        TipsManager.getInstance(context).tipsEnabled = false
    }

    @Test
    fun `row and switch taps update the same saved state and preset visibility`() {
        ActivityScenario.launch(TipsSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<MaterialSwitch>(R.id.tips_enabled_switch)
                val presets = activity.findViewById<View>(R.id.presets_container)
                activity.findViewById<View>(R.id.enable_tips_row).performClick()
                assertTrue(toggle.isChecked)
                assertTrue(TipsManager.getInstance(activity).tipsEnabled)
                assertEquals(View.VISIBLE, presets.visibility)
                toggle.performClick()
                assertFalse(TipsManager.getInstance(activity).tipsEnabled)
                assertEquals(View.GONE, presets.visibility)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<MaterialSwitch>(R.id.tips_enabled_switch).isChecked)
            }
        }
    }
}
