package com.electricdreams.numo.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinProtectionHelper
import com.electricdreams.numo.feature.settings.StripeCardSetupActivity
import com.electricdreams.numo.payment.card.StripeCardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingCardPaymentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetOnboarding() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        OnboardingActivity.setOnboardingComplete(context, false)
        StripeCardSettings.getInstance(context).clear()
        ReflectionHelpers.setStaticField(PinManager::class.java, "instance", null)
        PinProtectionHelper.clearVerification()
    }

    @Test
    fun `connect Stripe is visible on launch and opens setup before wallet creation`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val connect = activity.findViewById<View>(R.id.connect_stripe_button)
                assertTrue(connect.isShown)
                connect.performClick()
                assertEquals(
                    StripeCardSetupActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className,
                )
                assertFalse(activity.isFinishing)
                assertFalse(OnboardingActivity.isOnboardingComplete(activity))
            }
        }
    }

    @Test
    fun `the second welcome slide offers Stripe even without NFC and can be skipped`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val tour = activity.findViewById<OnboardingTourView>(R.id.welcome_tour)
                tour.advancePage()
                val next = activity.findViewById<TextView>(R.id.accept_button)
                assertEquals(activity.getString(R.string.onboarding_card_payments_skip), next.text)
                assertTrue(activity.findViewById<View>(R.id.connect_stripe_button).isShown)
                next.performClick()
                assertTrue(activity.findViewById<View>(R.id.create_wallet_button).isShown)
                assertTrue(activity.findViewById<View>(R.id.restore_wallet_button).isShown)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(StripeCardSettings.getInstance(activity).isEnabled)
            }
        }
    }

    @Test
    fun `a configured PIN must be verified before entering Stripe setup`() {
        context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("pin_enabled", true)
            .putString("encrypted_pin", "test")
            .apply()
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val connect = activity.findViewById<View>(R.id.connect_stripe_button)
                val shadow = shadowOf(activity)
                connect.performClick()
                val pin = shadow.nextStartedActivity
                assertEquals(PinEntryActivity::class.java.name, pin.component?.className)
                shadow.receiveResult(pin, Activity.RESULT_CANCELED, null)
                assertFalse(activity.isFinishing)
                assertNull(shadow.nextStartedActivity)

                connect.performClick()
                shadow.receiveResult(shadow.nextStartedActivity, Activity.RESULT_OK, Intent())
                assertEquals(
                    StripeCardSetupActivity::class.java.name,
                    shadow.nextStartedActivity.component?.className,
                )
            }
        }
    }
}
