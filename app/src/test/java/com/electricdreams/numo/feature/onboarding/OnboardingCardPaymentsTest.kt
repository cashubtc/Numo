package com.electricdreams.numo.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
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
    fun `mint setup leads to a dedicated screen with Connect Stripe above Not now`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val connect = activity.findViewById<TextView>(R.id.connect_stripe_button)
                assertFalse(connect.isShown)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "onWalletReady")
                val skip = activity.findViewById<TextView>(R.id.skip_stripe_button)
                val actions = connect.parent as ViewGroup
                assertTrue(connect.isShown)
                assertTrue(skip.isShown)
                assertFalse(activity.findViewById<View>(R.id.welcome_tour).isShown)
                assertEquals(
                    activity.getColor(R.color.color_text_on_dark),
                    activity.findViewById<TextView>(R.id.card_payments_title).currentTextColor,
                )
                assertEquals(
                    activity.getColor(R.color.onboarding_tour_secondary),
                    activity.findViewById<TextView>(R.id.card_payments_body).currentTextColor,
                )
                assertEquals(activity.getString(R.string.stripe_card_get_started), connect.text)
                assertEquals(activity.getString(R.string.onboarding_card_payments_skip), skip.text)
                assertTrue(actions.indexOfChild(connect) < actions.indexOfChild(skip))
                connect.performClick()
                assertEquals(
                    StripeCardSetupActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className,
                )
                assertFalse(activity.isFinishing)
                assertTrue(activity.findViewById<View>(R.id.card_payments_container).isShown)
                assertTrue(OnboardingActivity.isOnboardingComplete(activity))
            }
            scenario.onActivity { StripeCardSettings.getInstance(it).hasPassedCheck = true }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.success_container).isShown)
            }
        }
    }

    @Test
    fun `the card offer and restored balance summary survive recreation and skipping`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ReflectionHelpers.setField(activity, "isRestoreFlow", true)
                ReflectionHelpers.getField<MutableMap<String, Pair<Long, Long>>>(
                    activity, "balanceChanges",
                )["https://example.test"] = 20L to 80L
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "onWalletReady")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.connect_stripe_button).isShown)
                activity.findViewById<View>(R.id.skip_stripe_button).performClick()
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(StripeCardSettings.getInstance(activity).isEnabled)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.success_container).isShown)
                assertEquals(
                    activity.getString(R.string.onboarding_success_restored_recovered, 60L),
                    activity.findViewById<TextView>(R.id.success_subtitle).text,
                )
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
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "onWalletReady")
                val connect = activity.findViewById<View>(R.id.connect_stripe_button)
                val shadow = shadowOf(activity)
                connect.performClick()
                val pin = shadow.nextStartedActivity
                assertEquals(PinEntryActivity::class.java.name, pin.component?.className)
                shadow.receiveResult(pin, Activity.RESULT_CANCELED, null)
                assertFalse(activity.isFinishing)
                assertTrue(connect.isShown)
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
