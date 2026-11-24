package com.electricdreams.numo.feature.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.SecretCipher
import com.electricdreams.numo.core.prefs.ThreadSafePreferences
import com.electricdreams.numo.payment.card.CardSetupChecker
import com.electricdreams.numo.payment.card.CardSetupChecker.ReaderResult
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeCardSettings
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardSettingsActivityTest {

    /** Robolectric's device has no NFC chip, which is exactly the unsupported case. */
    @Test
    fun `a phone without NFC is told it cannot take cards and is not offered setup`() {
        ActivityScenario.launch(StripeCardSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val card = activity.onlyCard()
                assertEquals("This device can't take cards", card.title())
                assertEquals(View.GONE, card.findViewById<View>(R.id.attention_fix_button).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.account_header).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.intro_container).visibility)
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.configured_container).visibility,
                )
            }
        }
    }

    private class FakeEnvironment(
        private val nfcEnabled: Boolean = true,
        private val locationGranted: Boolean = true,
        private val stripeReachable: Boolean = true,
    ) : CardSetupChecker.Environment {
        override fun isDeviceSupported() = true
        override fun isNfcEnabled() = nfcEnabled
        override fun hasLocationPermission() = locationGranted
        override fun loadConfig() = StripeCardConfig("rk_test_abc123", "tml_shop")
        override suspend fun locationStatus(config: StripeCardConfig) =
            CardSetupChecker.LocationStatus.FOUND
        override suspend fun createConnectionToken(config: StripeCardConfig) {
            if (!stripeReachable) throw IOException("no route to host")
        }
        override suspend fun startReader(config: StripeCardConfig) = ReaderResult.Started
    }

    /** Reversible stand-in for the Android Keystore, which Robolectric does not provide. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(ciphertext: String): String? = ciphertext.removePrefix("enc:").reversed()
    }

    /** A connected, checked and paused account whose location has a name, so nothing asks Stripe. */
    private fun connectedSettings(): StripeCardSettings = StripeCardSettings(
        ThreadSafePreferences(ApplicationProvider.getApplicationContext(), "stripe_card_ui_test"),
        FakeCipher(),
    ).apply {
        assertTrue(saveConfig(StripeCardConfig("rk_test_abc123", "tml_shop")))
        saveLocationName("Shop")
        // Saving a new key and location asks for a fresh check; these tests start after one passed.
        hasPassedCheck = true
    }

    private fun StripeCardSettingsActivity.onlyCard(): View {
        val cards = findViewById<ViewGroup>(R.id.attention_list)
        assertEquals(1, cards.childCount)
        return cards.getChildAt(0)
    }

    private fun View.title() = findViewById<TextView>(R.id.attention_title).text.toString()

    private fun StripeCardSettingsActivity.trailing(row: Int) =
        findViewById<View>(row).findViewById<TextView>(R.id.row_trailing_text).text.toString()

    private fun StripeCardSettingsActivity.status() = trailing(R.id.status_row)

    private fun showWith(
        environment: FakeEnvironment,
        online: Boolean = true,
        enabled: Boolean = false,
        assertions: (StripeCardSettingsActivity) -> Unit,
    ) {
        ActivityScenario.launch(StripeCardSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings = connectedSettings().also { it.isEnabled = enabled }
                activity.checkerFactory = { CardSetupChecker(environment) }
                activity.isOnline = { online }
                activity.refresh()
                shadowOf(activity.mainLooper).idle()
                assertions(activity)
            }
        }
    }

    @Test
    fun `a missing location permission shows one card that names it and offers the fix`() {
        showWith(FakeEnvironment(locationGranted = false)) { activity ->
            val cards = activity.findViewById<ViewGroup>(R.id.attention_list)
            assertEquals(1, cards.childCount)
            val card = cards.getChildAt(0)
            assertEquals(
                "Numo can't use location",
                card.findViewById<TextView>(R.id.attention_title).text.toString(),
            )
            assertEquals(
                "Stripe won't take card payments without location access.",
                card.findViewById<TextView>(R.id.attention_body).text.toString(),
            )
            assertEquals(
                "Allow location",
                card.findViewById<TextView>(R.id.attention_fix_button).text.toString(),
            )
            assertEquals("Needs fixing", activity.status())
        }
    }

    @Test
    fun `two problems show two cards`() {
        showWith(
            FakeEnvironment(nfcEnabled = false, locationGranted = false, stripeReachable = false),
            online = false,
        ) { activity ->
            assertEquals(2, activity.findViewById<ViewGroup>(R.id.attention_list).childCount)
        }
    }

    @Test
    fun `NFC off while online is left to the strip`() {
        showWith(FakeEnvironment(nfcEnabled = false, locationGranted = false)) { activity ->
            assertEquals("Numo can't use location", activity.onlyCard().title())
        }
    }

    @Test
    fun `a passing check shows the shop, a plain status and no card`() {
        showWith(FakeEnvironment()) { activity ->
            assertEquals(0, activity.findViewById<ViewGroup>(R.id.attention_list).childCount)
            assertEquals("Paused", activity.status())
            assertEquals("Shop", activity.findViewById<TextView>(R.id.account_title).text.toString())
            assertEquals(
                "Stripe test mode",
                activity.findViewById<TextView>(R.id.account_subtitle).text.toString(),
            )
            assertEquals("Never", activity.trailing(R.id.check_row))
            assertEquals("Shop", activity.trailing(R.id.location_row))
        }
    }

    @Test
    fun `card payments switched on read as ready`() {
        showWith(FakeEnvironment(), enabled = true) { activity ->
            assertEquals("Ready", activity.status())
        }
    }

    @Test
    fun `Stripe out of reach while online shows a card with Try again`() {
        showWith(FakeEnvironment(stripeReachable = false)) { activity ->
            val cards = activity.findViewById<ViewGroup>(R.id.attention_list)
            assertEquals(1, cards.childCount)
            assertEquals(
                "Couldn't reach Stripe",
                cards.getChildAt(0).findViewById<TextView>(R.id.attention_title).text.toString(),
            )
        }
    }

    @Test
    fun `offline leaves the explaining to the Offline strip and keeps the last known status`() {
        showWith(FakeEnvironment(stripeReachable = false), online = false) { activity ->
            assertEquals(0, activity.findViewById<ViewGroup>(R.id.attention_list).childCount)
            assertEquals("Paused", activity.status())
        }
    }

    @Test
    fun `offline does not hide a problem the merchant can fix`() {
        showWith(
            FakeEnvironment(locationGranted = false, stripeReachable = false),
            online = false,
        ) { activity ->
            val cards = activity.findViewById<ViewGroup>(R.id.attention_list)
            assertEquals(1, cards.childCount)
            assertEquals(
                "Numo can't use location",
                cards.getChildAt(0).findViewById<TextView>(R.id.attention_title).text.toString(),
            )
        }
    }
}
