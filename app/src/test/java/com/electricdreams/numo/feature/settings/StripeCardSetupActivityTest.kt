package com.electricdreams.numo.feature.settings

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.RadioGroup
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.util.Locale
import java.util.concurrent.TimeUnit
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
                val field = activity.findViewById<EditText>(R.id.stripe_key_input)
                assertTrue(field.transformationMethod is PasswordTransformationMethod)
                assertFalse(field.isSaveEnabled)
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
    fun `stripe key creation requests location read rather than write`() {
        ActivityScenario.launch(StripeCardSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.open_stripe_button).performClick()

                val url = shadowOf(activity).nextStartedActivity.dataString.orEmpty()
                assertTrue(url.contains("rak_terminal_connection_token_write"))
                assertTrue(url.contains("rak_terminal_location_read"))
                assertFalse(url.contains("rak_terminal_location_write"))
            }
        }
    }

    @Test
    fun `a key that cannot list locations accepts a pasted location ID`() {
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
                    val settings = StripeCardSettings(
                        ThreadSafePreferences(activity, "stripe_card_setup_test"),
                        FakeCipher(),
                    ).also { it.clear() }
                    activity.settings = settings
                    activity.locationsClient = StripeLocationsClient(server.url("/"))
                    activity.findViewById<TextInputEditText>(R.id.stripe_key_input)
                        .setText("rk_test_abc123")
                    val next = activity.findViewById<View>(R.id.next_button)
                    next.performClick()

                    activity.idleUntil {
                        activity.findViewById<View>(R.id.location_container).visibility == View.VISIBLE
                    }

                    assertNull(activity.findViewById<TextInputLayout>(R.id.stripe_key_layout).error)
                    assertTrue(settings.hasStoredKey())
                    val input = activity.findViewById<TextInputEditText>(R.id.location_id_input)
                    input.setText("not-a-location")
                    next.performClick()
                    assertEquals(
                        activity.getString(R.string.stripe_card_location_id_invalid),
                        activity.findViewById<TextInputLayout>(R.id.location_id_layout).error?.toString(),
                    )
                    assertFalse(activity.isFinishing)

                    input.setText(" tml_manual1 ")
                    next.performClick()
                    activity.idleUntil { activity.isFinishing }
                    assertEquals("tml_manual1", settings.storedLocationId())
                    assertNull(settings.storedLocationName())
                    assertEquals(1, server.requestCount)
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
        assertions: (StripeCardSetupActivity, StripeCardSettings, MockWebServer) -> Unit,
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

                    activity.idleUntil {
                        activity.isFinishing || activity.findViewById<View>(R.id.key_container).visibility == View.GONE
                    }
                    assertEquals("", activity.findViewById<EditText>(R.id.stripe_key_input).text.toString())
                    server.takeRequest()
                    assertions(activity, settings, server)
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an account with one location uses it without asking`() {
        submitKey(location("tml_only1", "Corner shop")) { activity, settings, _ ->
            assertEquals("tml_only1", settings.storedLocationId())
            assertEquals("Corner shop", settings.storedLocationName())
            assertTrue(activity.isFinishing)
        }
    }

    @Test
    fun `an account with several locations asks which one`() {
        submitKey(location("tml_one1", "Corner shop"), location("tml_two2", "Market stall")) { activity, settings, _ ->
            assertEquals("", settings.storedLocationId())
            assertFalse(activity.isFinishing)
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.location_container).visibility,
            )
        }
    }

    @Test
    fun `a pasted location ID can replace a listed choice`() {
        submitKey(
            location("tml_one1", "Corner shop"),
            location("tml_two2", "Market stall"),
        ) { activity, settings, server ->
            activity.findViewById<TextInputEditText>(R.id.location_id_input)
                .setText("tml_manual2")
            activity.findViewById<View>(R.id.next_button).performClick()
            activity.idleUntil { activity.isFinishing }

            assertEquals("tml_manual2", settings.storedLocationId())
            assertNull(settings.storedLocationName())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `an account with no location offers adding one or pasting its ID`() {
        submitKey { activity, settings, _ ->
            assertEquals("", settings.storedLocationId())
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.location_container).visibility,
            )
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.add_location_button).visibility,
            )
            assertEquals(
                View.VISIBLE,
                activity.findViewById<View>(R.id.location_id_input).visibility,
            )
        }
    }

    @Test
    fun `a failed location save leaves the picker open with the same selection`() {
        submitKey(location("tml_old", "Old"), location("tml_new", "New")) { activity, settings, _ ->
            settings.saveLocation("tml_old", "Old")
            activity.settings = spy(settings).also {
                doReturn(false).whenever(it).saveLocation("tml_new", "New", "Berlin, DE")
            }
            val list = activity.findViewById<RadioGroup>(R.id.location_list)
            list.check(list.findViewWithTag<View>("tml_new").id)
            activity.findViewById<View>(R.id.next_button).performClick()
            activity.idleUntil { ShadowToast.getTextOfLatestToast() != null }
            assertEquals(activity.getString(R.string.stripe_card_location_save_failed),
                ShadowToast.getTextOfLatestToast().toString())
            assertEquals("tml_old", settings.storedLocationId())
            assertEquals("tml_new", list.findViewById<View>(list.checkedRadioButtonId).tag)
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun `country rules validate before submission and retries reuse the request key`() {
        submitKey { activity, _, server ->
            activity.findViewById<View>(R.id.add_location_button).performClick()
            fun field(id: Int) = activity.findViewById<EditText>(id)
            fun country(code: String) {
                activity.findViewById<AutoCompleteTextView>(R.id.country_input)
                    .setText(Locale("", code).getDisplayCountry(Locale.getDefault()), false)
            }
            val next = activity.findViewById<View>(R.id.next_button)
            field(R.id.shop_name_input).setText("Shop")
            field(R.id.street_input).setText("1 Main")
            country("US")
            next.performClick()
            for (id in listOf(R.id.city_layout, R.id.state_layout, R.id.postal_layout)) {
                assertNotNull(activity.findViewById<TextInputLayout>(id).error)
            }
            country("MX")
            next.performClick()
            assertNotNull(activity.findViewById<TextInputLayout>(R.id.unit_layout).error)
            assertEquals(1, server.requestCount)

            country("HK")
            field(R.id.state_input).setText("Hong Kong Island")
            fun failedSubmission(): String {
                server.enqueue(MockResponse().setResponseCode(503))
                next.performClick()
                activity.idleUntil { activity.findViewById<View>(R.id.add_location_error).isShown }
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val body = request.body.readUtf8()
                assertFalse(body.contains("city"))
                assertFalse(body.contains("postal_code"))
                return requireNotNull(request.getHeader("Idempotency-Key"))
            }
            val firstId = failedSubmission()
            assertEquals(firstId, failedSubmission())
            field(R.id.shop_name_input).setText("New name")
            assertNotEquals(firstId, failedSubmission())
        }
    }

}
