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
