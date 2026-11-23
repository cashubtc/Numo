package com.electricdreams.numo.feature.settings

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardSettingsActivityTest {

    /** Robolectric's device has no NFC chip, which is exactly the unsupported case. */
    @Test
    fun `a phone without NFC is told it cannot take cards and is not offered setup`() {
        ActivityScenario.launch(StripeCardSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.test_mode_banner).visibility,
                )
                assertEquals(
                    "This device can't take cards",
                    activity.findViewById<TextView>(R.id.status_headline).text.toString(),
                )
                assertEquals(View.GONE, activity.findViewById<View>(R.id.intro_container).visibility)
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.configured_container).visibility,
                )
            }
        }
    }
}
