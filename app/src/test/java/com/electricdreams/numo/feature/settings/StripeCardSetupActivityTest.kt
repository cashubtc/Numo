package com.electricdreams.numo.feature.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardSetupActivityTest {

    @Before
    fun setUp() {
        StripeCardSettings.getInstance(ApplicationProvider.getApplicationContext()).clear()
    }

    @Test
    fun `without a stored key the field is an empty prompt`() {
        ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val layout = activity.findViewById<TextInputLayout>(R.id.stripe_key_layout)
                assertNull(layout.placeholderText)
                assertNull(layout.helperText)
                assertTrue(layout.isExpandedHintEnabled)
            }
        }
    }

    @Test
    fun `a stored key shows masked in the field without becoming its text`() {
        ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showCurrentKey("rk_test_…dtS3")

                val layout = activity.findViewById<TextInputLayout>(R.id.stripe_key_layout)
                val input = activity.findViewById<TextInputEditText>(R.id.stripe_key_input)
                assertEquals("rk_test_…dtS3", layout.placeholderText.toString())
                assertEquals("Paste a new key to replace it.", layout.helperText.toString())
                assertFalse(layout.isExpandedHintEnabled)
                assertEquals("", input.text.toString())
            }
        }
    }
}
