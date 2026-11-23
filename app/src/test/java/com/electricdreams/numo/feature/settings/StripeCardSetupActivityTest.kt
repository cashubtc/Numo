package com.electricdreams.numo.feature.settings

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.SecretCipher
import com.electricdreams.numo.core.prefs.ThreadSafePreferences
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.payment.card.StripeLocationsClient
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardSetupActivityTest {

    @Before
    fun setUp() {
        StripeCardSettings.getInstance(ApplicationProvider.getApplicationContext()).clear()
    }

    @Test
    fun `without a stored key the field is an empty prompt`() {
        ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val layout = activity.findViewById<TextInputLayout>(R.id.stripe_key_layout)
                assertNull(layout.placeholderText)
                assertNull(layout.helperText)
                assertTrue(layout.isExpandedHintEnabled)
            }
        }
    }

    @Test
    fun `a stored key shows masked in the field without becoming its text`() {
        ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showCurrentKey("rk_test_…dtS3")

                val layout = activity.findViewById<TextInputLayout>(R.id.stripe_key_layout)
                val input = activity.findViewById<TextInputEditText>(R.id.stripe_key_input)
                assertEquals("rk_test_…dtS3", layout.placeholderText.toString())
                assertEquals("Paste a new key to replace it.", layout.helperText.toString())
                assertFalse(layout.isExpandedHintEnabled)
                assertEquals("", input.text.toString())
            }
        }
    }

    @Test
    fun `a key that cannot list locations is refused on the key step`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"type":"invalid_request_error","message":"Missing permission"}}"""
            )
        )
        server.start()
        try {
            ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.locationsClient = StripeLocationsClient(server.url("/"))
                    val layout = activity.findViewById<TextInputLayout>(R.id.stripe_key_layout)
                    activity.findViewById<TextInputEditText>(R.id.stripe_key_input)
                        .setText("rk_test_abc123")
                    activity.findViewById<View>(R.id.next_button).performClick()

                    // Stripe is asked on a background thread; give its answer time to land.
                    val deadline = System.currentTimeMillis() + 5_000
                    while (layout.error == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20)
                        shadowOf(activity.mainLooper).idle()
                    }

                    assertEquals(
                        "This key can't see your locations. In Stripe, give it Write access to " +
                            "Terminal Locations, then paste it again.",
                        layout.error?.toString(),
                    )
                    assertEquals(
                        View.VISIBLE,
                        activity.findViewById<View>(R.id.key_container).visibility,
                    )
                }
            }
        } finally {
            server.shutdown()
        }
    }

    /** Reversible stand-in for the Android Keystore, which Robolectric does not provide. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(ciphertext: String): String? = ciphertext.removePrefix("enc:").reversed()
    }

    private fun location(id: String, name: String) =
        """{"id":"$id","display_name":"$name","address":{"city":"Berlin","country":"DE"}}"""

    /** Pastes a key into a fresh setup whose Stripe account has [locations], and waits for the answer. */
    private fun submitKey(
        vararg locations: String,
        assertions: (StripeCardSetupActivity, StripeCardSettings) -> Unit,
    ) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[${locations.joinToString(",")}]}"""))
        server.start()
        try {
            ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val settings = StripeCardSettings(
                        ThreadSafePreferences(activity, "stripe_card_setup_test"),
                        FakeCipher(),
                    ).also { it.clear() }
                    activity.settings = settings
                    activity.locationsClient = StripeLocationsClient(server.url("/"))
                    activity.findViewById<TextInputEditText>(R.id.stripe_key_input)
                        .setText("rk_test_abc123")
                    activity.findViewById<View>(R.id.next_button).performClick()

                    val deadline = System.currentTimeMillis() + 5_000
                    while (!settings.hasStoredKey() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20)
                        shadowOf(activity.mainLooper).idle()
                    }
                    shadowOf(activity.mainLooper).idle()
                    assertions(activity, settings)
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an account with one location uses it without asking`() {
        submitKey(location("tml_only1", "Corner shop")) { activity, settings ->
            assertEquals("tml_only1", settings.storedLocationId())
            assertEquals("Corner shop", settings.storedLocationName())
            assertTrue(activity.isFinishing)
        }
    }

    @Test
    fun `an account with several locations asks which one`() {
        submitKey(location("tml_one1", "Corner shop"), location("tml_two2", "Market stall")) { activity, settings ->
            assertEquals("", settings.storedLocationId())
            assertFalse(activity.isFinishing)
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.location_container).visibility,
            )
        }
    }

    @Test
    fun `an account with no location goes straight to adding one`() {
        submitKey { activity, settings ->
            assertEquals("", settings.storedLocationId())
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.add_location_container).visibility,
            )
        }
    }
}
