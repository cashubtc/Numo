package com.electricdreams.numo.feature.settings

import android.Manifest
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.electricdreams.numo.R
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeCardConnectionTester
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardSettingsActivityTest {

    @Test
    fun `enable waits for reader validation to succeed`() {
        val allowValidation = CompletableDeferred<Unit>()
        mockConstruction(StripeCardConnectionTester::class.java) { tester, _ ->
            whenever(runBlocking { tester.validate(any(), any()) }).doSuspendableAnswer {
                allowValidation.await()
            }
        }.use {
            ActivityScenario.launch(StripeCardSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val settings = installSettings(activity)
                    prepareValidForm(activity)
                    activity.findViewById<MaterialSwitch>(R.id.stripe_card_enable_switch).apply {
                        isEnabled = true
                        toggle()
                    }
                    shadowOf(Looper.getMainLooper()).idle()

                    verify(settings, never()).isEnabled = true
                    allowValidation.complete(Unit)
                    shadowOf(Looper.getMainLooper()).idle()

                    verify(settings).isEnabled = true
                }
            }
        }
    }

    @Test
    fun `reader compatibility failure is reported and does not enable cards`() {
        mockConstruction(StripeCardConnectionTester::class.java) { tester, _ ->
            whenever(runBlocking { tester.validate(any(), any()) }).thenThrow(
                IllegalStateException("Device does not use Trusted Execution Environment")
            )
        }.use {
            ActivityScenario.launch(StripeCardSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val settings = installSettings(activity)
                    prepareValidForm(activity)
                    activity.findViewById<MaterialSwitch>(R.id.stripe_card_enable_switch).apply {
                        isEnabled = true
                        toggle()
                    }
                    shadowOf(Looper.getMainLooper()).idle()

                    verify(settings, never()).isEnabled = true
                    val status = activity.findViewById<TextView>(R.id.test_connection_status).text.toString()
                    assertTrue(status.contains("Trusted Execution Environment"))
                }
            }
        }
    }

    private fun installSettings(activity: StripeCardSettingsActivity): StripeCardSettings {
        val settings = mock<StripeCardSettings>()
        whenever(settings.loadConfig()).thenReturn(
            StripeCardConfig(restrictedKey = "rk_test_123", locationId = "tml_123")
        )
        ReflectionHelpers.setField(activity, "settings", settings)
        return settings
    }

    private fun prepareValidForm(activity: StripeCardSettingsActivity) {
        shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        activity.findViewById<TextInputEditText>(R.id.stripe_restricted_key_input)
            .setText("rk_test_123")
        activity.findViewById<TextInputEditText>(R.id.stripe_location_input)
            .setText("tml_123")
    }
}
