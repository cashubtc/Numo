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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeTerminalManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPreferencesUiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPreferences() {
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

    @Test
    fun `Stripe reader defaults for the build type and uses the saved developer switch`() {
        val config = StripeCardConfig("rk_test_example", "tml_example")
        DeveloperPrefs.setDeveloperModeEnabled(context, true)
        ActivityScenario.launch(DeveloperSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<MaterialSwitch>(R.id.simulate_stripe_reader_switch)
                assertEquals(BuildConfig.DEBUG, toggle.isChecked)
                assertEquals(BuildConfig.DEBUG,
                    StripeTerminalManager.readerDiscoveryConfig(activity, config).isSimulated)

                activity.findViewById<android.view.View>(R.id.simulate_stripe_reader_item).performClick()
                assertEquals(!BuildConfig.DEBUG, toggle.isChecked)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<MaterialSwitch>(R.id.simulate_stripe_reader_switch)
                assertEquals(!BuildConfig.DEBUG, toggle.isChecked)
                assertEquals(!BuildConfig.DEBUG,
                    StripeTerminalManager.readerDiscoveryConfig(activity, config).isSimulated)

                toggle.performClick()
                assertEquals(BuildConfig.DEBUG,
                    StripeTerminalManager.readerDiscoveryConfig(activity, config).isSimulated)
            }
        }
    }

    @Test
    fun `simulated Stripe reader rejects live credentials before discovery`() {
        val config = StripeCardConfig("rk_live_example", "tml_example")
        DeveloperPrefs.setDeveloperModeEnabled(context, true)
        DeveloperPrefs.setStripeReaderSimulated(context, true)
        val failure = assertThrows(StripeTerminalManager.CardPaymentException::class.java) {
            StripeTerminalManager.readerDiscoveryConfig(context, config)
        }
        assertEquals(StripeTerminalManager.FailureReason.SIMULATION_REQUIRES_TEST_KEY, failure.reason)
        DeveloperPrefs.setDeveloperModeEnabled(context, false)
        assertFalse(StripeTerminalManager.readerDiscoveryConfig(context, config).isSimulated)
    }
}
