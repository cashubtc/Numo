package com.electricdreams.numo.feature.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.payment.DefaultPaymentMethodManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPreferencesUiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPreferences() {
        DefaultPaymentMethodManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        DefaultPaymentMethodManager.getInstance(context).setDefaultPaymentMethod(PaymentTab.UNIFIED)
        PreferenceStore.app(context).putString(ThemeSettingsActivity.PREF_THEME, ThemeSettingsActivity.THEME_GREEN)
        PreferenceStore.app(context).putBoolean("darkMode", false)
        DeveloperPrefs.setLightningInvoiceDelayed(context, false)
    }

    @After
    fun restoreAppearance() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun `payment choices restore the saved option and persist row taps`() {
        DefaultPaymentMethodManager.getInstance(context).setDefaultPaymentMethod(PaymentTab.LIGHTNING)
        ActivityScenario.launch(DefaultPaymentMethodSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val lightning = activity.findViewById<MaterialRadioButton>(R.id.radio_lightning)
                val cashu = activity.findViewById<MaterialRadioButton>(R.id.radio_cashu)
                assertTrue(lightning.isChecked)
                cashu.performClick()
                assertTrue(cashu.isChecked)
                assertFalse(lightning.isChecked)
                assertEquals(PaymentTab.CASHU,
                    DefaultPaymentMethodManager.getInstance(activity).getDefaultPaymentMethod())
            }
        }
    }

    @Test
    fun `theme choices preserve selection and save mode changes`() {
        ActivityScenario.launch(ThemeSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<MaterialRadioButton>(R.id.radio_green).isChecked)
                activity.findViewById<MaterialRadioButton>(R.id.radio_bitcoin_orange).performClick()
                assertEquals(ThemeSettingsActivity.THEME_BITCOIN_ORANGE,
                    PreferenceStore.app(activity).getString(ThemeSettingsActivity.PREF_THEME))
                activity.findViewById<MaterialSwitch>(R.id.dark_mode_switch).performClick()
                assertTrue(PreferenceStore.app(activity).getBoolean("darkMode", false))
            }
        }
    }

    @Test
    fun `language choices apply the selected app locale`() {
        ActivityScenario.launch(LanguageSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<MaterialRadioButton>(R.id.radio_german).performClick()
                assertEquals("de", AppCompatDelegate.getApplicationLocales()[0]?.language)
            }
        }
    }

    @Test
    fun `developer toggle remains actionable from the full row`() {
        ActivityScenario.launch(DeveloperSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.delay_lightning_invoice_item).performClick()
                assertTrue(DeveloperPrefs.isLightningInvoiceDelayed(activity))
                activity.findViewById<MaterialSwitch>(R.id.delay_lightning_invoice_switch).performClick()
                assertFalse(DeveloperPrefs.isLightningInvoiceDelayed(activity))
            }
        }
    }
}
