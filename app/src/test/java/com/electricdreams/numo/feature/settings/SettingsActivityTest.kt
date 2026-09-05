package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider

import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.ActivitySettingsBinding
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinProtectionHelper
import com.electricdreams.numo.payment.DefaultPaymentMethodManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivityTest {

    @Before
    fun resetPreferences() {
        listOf(PinManager::class.java, MintManager::class.java,
            DefaultPaymentMethodManager::class.java).forEach { manager ->
            manager.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceStore.app(context).putBoolean("btcpay_enabled", false)
        DeveloperPrefs.setDeveloperModeEnabled(context, false)
        MintManager.getInstance(context).setPreferredUnit("sat")
        DefaultPaymentMethodManager.getInstance(context).setDefaultPaymentMethod(PaymentTab.UNIFIED)
        context.getSharedPreferences("pin_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        PinProtectionHelper.clearVerification()
    }

    @Test
    fun `search matches descriptions and clearing restores sections`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val binding = bind(controller.get())
            binding.settingsSearch.setText("  CATALOG  ")
            assertEquals(View.VISIBLE, binding.itemsSettingsItem.visibility)
            assertEquals(View.GONE, binding.tipsSettingsItem.visibility)
            assertEquals(View.GONE, binding.paymentsSection.visibility)
            assertEquals(View.GONE, binding.searchEmptyState.visibility)

            binding.settingsSearch.setText("")
            assertEquals(View.VISIBLE, binding.tipsSettingsItem.visibility)
            assertEquals(View.VISIBLE, binding.paymentsSection.visibility)
            assertEquals(View.GONE, binding.developerSection.visibility)
        }
    }

    @Test
    fun `search shows empty state and never reveals hidden developer options`() {
        withSettings { _, binding ->
            binding.settingsSearch.setText("debugging")
            assertEquals(View.VISIBLE, binding.searchEmptyState.visibility)
            assertEquals(View.GONE, binding.developerSection.visibility)
            binding.settingsSearch.setText("nothing matches this query")
            assertEquals(View.VISIBLE, binding.searchEmptyState.visibility)
        }
    }

    @Test
    fun `section search finds all rows and summaries remain searchable`() {
        withSettings { _, binding ->
            binding.settingsSearch.setText("connections")
            assertEquals(View.VISIBLE, binding.btcpaySettingsItem.visibility)
            assertEquals(View.VISIBLE, binding.webhooksSettingsItem.visibility)
            assertEquals(View.GONE, binding.checkoutSection.visibility)
            binding.settingsSearch.setText("unified")
            assertEquals(View.VISIBLE, binding.defaultPaymentMethodSettingsItem.visibility)
        }
    }

    @Test
    fun `btcpay restrictions explain why and refresh when returning to settings`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val binding = bind(activity)
            controller.pause()
            PreferenceStore.app(activity).putBoolean("btcpay_enabled", true)
            controller.resume()
            val restricted = listOf(binding.mintsSettingsItem, binding.withdrawalsSettingsItem,
                binding.webhooksSettingsItem)
            restricted.forEach { row ->
                assertFalse(row.isEnabled)
                assertEquals(activity.getString(R.string.settings_unavailable_btcpay),
                    row.findViewById<TextView>(R.id.row_subtitle).text)
                assertEquals(View.GONE, row.findViewById<View>(R.id.row_trailing_icon).visibility)
                row.performClick()
                assertNull(shadowOf(activity).nextStartedActivity)
            }
            assertTrue(binding.btcpaySettingsItem.isEnabled)

            controller.pause()
            PreferenceStore.app(activity).putBoolean("btcpay_enabled", false)
            controller.resume()
            restricted.forEach { assertTrue(it.isEnabled) }
            binding.mintsSettingsItem.performClick()
            assertEquals(MintsSettingsActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
        }
    }

    @Test
    fun `custom units explain unavailable conversion and prevent navigation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MintManager.getInstance(context).setPreferredUnit("usd")
        withSettings { activity, binding ->
            assertFalse(binding.currencySettingsItem.isEnabled)
            assertEquals(activity.getString(R.string.settings_fiat_conversion_disabled_custom_unit),
                binding.currencySettingsItem.findViewById<TextView>(R.id.row_subtitle).text)
            binding.currencySettingsItem.performClick()
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun `returning updates payment selection and developer visibility`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val binding = bind(activity)
            controller.pause()
            DeveloperPrefs.setDeveloperModeEnabled(activity, true)
            DefaultPaymentMethodManager.getInstance(activity).setDefaultPaymentMethod(PaymentTab.CASHU)
            controller.resume()
            assertEquals(View.VISIBLE, binding.developerSection.visibility)
            assertEquals(activity.getString(R.string.default_payment_method_option_cashu),
                binding.defaultPaymentMethodSettingsItem.findViewById<TextView>(R.id.row_subtitle).text)
            binding.settingsSearch.setText("debugging")
            assertEquals(View.VISIBLE, binding.developerSettingsItem.visibility)
            assertEquals(View.GONE, binding.searchEmptyState.visibility)
        }
    }

    @Test
    fun `sensitive settings require pin while general settings remain accessible`() {
        withSettings { activity, binding ->
            activity.getSharedPreferences("pin_prefs", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("pin_enabled", true)
                .putString("encrypted_pin", "test-pin-ciphertext")
                .apply()
            listOf(binding.itemsSettingsItem, binding.mintsSettingsItem, binding.withdrawalsSettingsItem,
                binding.webhooksSettingsItem, binding.btcpaySettingsItem).forEach { row ->
                row.performClick()
                assertEquals(PinEntryActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className)
            }
            binding.languageSettingsItem.performClick()
            assertEquals(LanguageSettingsActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
            binding.securitySettingsItem.performClick()
            assertEquals(SecuritySettingsActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
        }
    }

    private fun withSettings(block: (SettingsActivity, ActivitySettingsBinding) -> Unit) {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            block(controller.get(), bind(controller.get()))
        }
    }

    private fun bind(activity: Activity): ActivitySettingsBinding {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        return ActivitySettingsBinding.bind(content.getChildAt(0))
    }
}
