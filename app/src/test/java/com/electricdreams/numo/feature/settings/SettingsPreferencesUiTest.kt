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

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test
    fun `preview follows every theme and restores selection after recreation`() {
        ActivityScenario.launch(ThemeSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preview = activity.findViewById<com.electricdreams.numo.ui.components.ThemePreviewView>(
                    R.id.theme_preview)
                val amount = preview.findViewById<android.widget.TextView>(R.id.amount_display)
                assertTrue("Sample amount must have visible bounds",
                    amount.width > 0 && amount.height > 0)
                assertTrue(amount.text.isNotBlank())
                val choices = listOf(
                    R.id.radio_white to ThemeSettingsActivity.THEME_WHITE,
                    R.id.radio_obsidian to ThemeSettingsActivity.THEME_OBSIDIAN,
                    R.id.radio_green to ThemeSettingsActivity.THEME_GREEN,
                    R.id.radio_bitcoin_orange to ThemeSettingsActivity.THEME_BITCOIN_ORANGE
                )
                choices.forEach { (id, theme) ->
                    activity.findViewById<MaterialRadioButton>(id).performClick()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofSeconds(1))
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        preview.width, preview.height, android.graphics.Bitmap.Config.ARGB_8888)
                    preview.draw(android.graphics.Canvas(bitmap))
                    assertEquals(com.electricdreams.numo.ui.theme.ThemeManager
                        .resolveBackgroundColor(activity, theme),
                        bitmap.getPixel(preview.width / 2, preview.height / 10))
                    bitmap.recycle()
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<MaterialRadioButton>(R.id.radio_bitcoin_orange)
                    .isChecked)
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
