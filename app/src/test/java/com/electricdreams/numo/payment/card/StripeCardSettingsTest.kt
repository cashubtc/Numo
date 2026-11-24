package com.electricdreams.numo.payment.card

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.prefs.SecretCipher
import com.electricdreams.numo.core.prefs.ThreadSafePreferences
import com.electricdreams.numo.payment.DefaultPaymentMethodManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StripeCardSettingsTest {

    /** Reversible stand-in for the Android Keystore, which Robolectric does not provide. */
    private class FakeCipher : SecretCipher {
        var failDecrypt = false
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(ciphertext: String): String? =
            if (failDecrypt) null else ciphertext.removePrefix("enc:").reversed()
    }

    private lateinit var context: Context
    private lateinit var prefs: ThreadSafePreferences
    private lateinit var cipher: FakeCipher
    private lateinit var settings: StripeCardSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric gives each test a fresh application, so this file starts empty.
        prefs = ThreadSafePreferences(context, "stripe_card_settings_test")
        cipher = FakeCipher()
        settings = StripeCardSettings(prefs, cipher)
    }

    @Test
    fun `only restricted keys are valid`() {
        assertTrue(StripeCardConfig.isValidRestrictedKey("rk_live_abc"))
        assertTrue(StripeCardConfig.isValidRestrictedKey(" rk_test_abc "))
        assertFalse(StripeCardConfig.isValidRestrictedKey("sk_live_abc"))
        assertFalse(StripeCardConfig.isValidRestrictedKey("pk_live_abc"))
        assertFalse(StripeCardConfig.isValidRestrictedKey("rk_test_"))
        assertFalse(StripeCardConfig.isValidRestrictedKey("rk_test_a b"))
        assertFalse(StripeCardConfig.isValidRestrictedKey("rk_test_é"))
    }

    @Test
    fun `location ids must be terminal locations`() {
        assertTrue(StripeCardConfig.isValidLocationId("tml_FDOt9wlaWDxwP7"))
        assertFalse(StripeCardConfig.isValidLocationId("tml_"))
        assertFalse(StripeCardConfig.isValidLocationId("loc_123"))
    }

    @Test
    fun `saved config round-trips and the key is not stored in plaintext`() {
        val config = StripeCardConfig("rk_live_secretvalue", "tml_shop")

        assertTrue(settings.saveConfig(config))

        assertEquals(config, settings.loadConfig())
        assertEquals("tml_shop", settings.storedLocationId())
        val stored = prefs.getString("stripe_card_restricted_key")
        assertNotEquals("rk_live_secretvalue", stored)
    }

    @Test
    fun `undecryptable key means card payments are not configured`() {
        settings.saveConfig(StripeCardConfig("rk_live_secretvalue", "tml_shop"))
        settings.isEnabled = true
        cipher.failDecrypt = true

        assertNull(settings.loadConfig())
        assertFalse(settings.isReady())
    }

    @Test
    fun `ready only when enabled and configured`() {
        assertFalse(settings.isReady())

        settings.saveConfig(StripeCardConfig("rk_test_secretvalue", "tml_shop"))
        assertFalse(settings.isReady())

        settings.isEnabled = true
        assertTrue(settings.isReady())
    }

    @Test
    fun `clear removes the key and disables card payments`() {
        settings.saveConfig(StripeCardConfig("rk_test_secretvalue", "tml_shop"))
        settings.isEnabled = true

        settings.clear()

        assertNull(settings.loadConfig())
        assertFalse(settings.isEnabled)
        assertFalse(settings.hasStoredKey())
        assertFalse(settings.hasPassedCheck)
        assertNull(settings.storedLocationName())
    }

    @Test
    fun `the key can be saved before a location is chosen`() {
        assertTrue(settings.saveKey(" rk_test_secretvalue1234 "))

        assertEquals("rk_test_secretvalue1234", settings.storedKey())
        assertNull(settings.loadConfig())
        assertEquals(true, settings.isTestMode())
        assertFalse(settings.saveKey("sk_test_secretvalue1234"))

        settings.saveLocation(" tml_shop ", " Corner shop ")

        assertEquals(StripeCardConfig("rk_test_secretvalue1234", "tml_shop"), settings.loadConfig())
        assertEquals("Corner shop", settings.storedLocationName())
    }

    @Test
    fun `the masked key shows the mode and the last characters only`() {
        settings.saveKey("rk_live_51AbCdEfGhIjKlMnOpdtS3")

        assertEquals("rk_live_…dtS3", settings.maskedKey())
        assertEquals("rk_test_…", StripeCardSettings.maskKey("rk_test_short"))
        assertEquals(false, settings.isTestMode())
    }

    @Test
    fun `changing the key or location turns card payments off until a check passes`() {
        settings.saveKey("rk_test_secretvalue1234")
        settings.saveLocation("tml_shop", "Corner shop")
        settings.hasPassedCheck = true
        settings.isEnabled = true

        settings.saveKey("rk_test_secretvalue1234")
        settings.saveLocation("tml_shop", "Corner shop renamed")
        assertTrue("saving the same values keeps card payments on", settings.isEnabled)

        settings.saveLocation("tml_other", null)
        assertFalse(settings.isEnabled)
        assertFalse(settings.hasPassedCheck)
        assertNull(settings.storedLocationName())

        settings.hasPassedCheck = true
        settings.saveKey("rk_test_anotherkey5678")
        assertFalse(settings.hasPassedCheck)
    }

    @Test
    fun `a setup enabled before the check existed counts as checked`() {
        prefs.putBoolean("stripe_card_enabled", true)

        assertTrue(settings.hasPassedCheck)
    }

    private val passingCheck = CardSetupChecker.Report(
        CardSetupChecker.Check.entries.associateWith { CardSetupChecker.Outcome.Passed }
    )

    @Test
    fun `an existing merchant pause survives both key and location edits`() {
        settings.saveKey("rk_test_first")
        settings.saveLocation("tml_old", "Old shop")
        // Paused by a version predating the separate enable preference.
        prefs.remove("stripe_card_enable_on_pass")
        prefs.putBoolean("stripe_card_check_passed", true)
        settings.saveKey("rk_test_second")
        settings.saveLocation("tml_new", "New shop")
        settings.recordFullCheck(settings.configurationRevision, passingCheck, requestedEnable = false)
        assertTrue(settings.hasPassedCheck)
        assertFalse(settings.isEnabled)
        settings.recordFullCheck(settings.configurationRevision, passingCheck, requestedEnable = true)
        assertTrue(settings.isEnabled)
    }

    @Test
    fun `legacy switch off stays paused after a key edit`() {
        prefs.putString("stripe_card_restricted_key", cipher.encrypt("rk_test_old"))
        prefs.putString("stripe_card_location_id", "tml_old")
        prefs.putBoolean("stripe_card_enabled", false)
        settings.saveKey("rk_test_new")
        settings.recordFullCheck(settings.configurationRevision, passingCheck, requestedEnable = false)
        assertFalse(settings.isEnabled)
    }

    @Test
    fun `a full check for a replaced configuration cannot enable payments`() {
        settings.saveKey("rk_test_old")
        val revision = settings.configurationRevision
        settings.saveKey("rk_test_new")
        assertFalse(settings.recordFullCheck(revision, passingCheck, requestedEnable = true))
        assertFalse(settings.isEnabled)
        assertNull(settings.lastCheckedAt)
    }

    @Test
    fun `config toString never includes the key`() {
        val text = StripeCardConfig("rk_live_secretvalue", "tml_shop").toString()

        assertFalse(text.contains("rk_live_secretvalue"))
    }

    @Test
    fun `card is returned as the default payment method`() {
        val manager = DefaultPaymentMethodManager.getInstance(context)

        manager.setDefaultPaymentMethod(PaymentTab.CARD)

        assertEquals(PaymentTab.CARD, manager.getDefaultPaymentMethod())
    }

    @Test
    fun `legacy bitcoin defaults resolve to unified bitcoin`() {
        val manager = DefaultPaymentMethodManager.getInstance(context)

        listOf(PaymentTab.CASHU, PaymentTab.LIGHTNING).forEach { legacyMethod ->
            manager.setDefaultPaymentMethod(legacyMethod)

            assertEquals(PaymentTab.UNIFIED, manager.getDefaultPaymentMethod())
        }
    }
}
