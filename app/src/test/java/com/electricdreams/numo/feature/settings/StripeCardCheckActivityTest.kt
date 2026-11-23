package com.electricdreams.numo.feature.settings

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.payment.card.CardSetupChecker
import com.electricdreams.numo.payment.card.CardSetupChecker.ReaderResult
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.payment.card.StripeConnectionTokenClient.StripeApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StripeCardCheckActivityTest {

    private class FakeEnvironment(
        var nfcEnabled: Boolean = true,
        var tokenError: IOException? = null,
    ) : CardSetupChecker.Environment {
        override fun isDeviceSupported() = true
        override fun isNfcEnabled() = nfcEnabled
        override fun hasLocationPermission() = true
        override fun loadConfig() = StripeCardConfig("rk_test_abc123", "tml_shop")
        override suspend fun locationStatus(config: StripeCardConfig) =
            CardSetupChecker.LocationStatus.FOUND
        override suspend fun createConnectionToken(config: StripeCardConfig) {
            tokenError?.let { throw it }
        }
        var readerStarts = 0
        override suspend fun startReader(config: StripeCardConfig): ReaderResult {
            readerStarts++
            return ReaderResult.Started
        }
    }

    private val settings: StripeCardSettings
        get() = StripeCardSettings.getInstance(ApplicationProvider.getApplicationContext())

    /** The preference store outlives a single test, so each one starts from nothing saved. */
    @Before
    fun setUp() {
        settings.clear()
    }

    /** Launches the screen, then re-runs the check against [environment]. */
    private fun runCheck(
        environment: FakeEnvironment,
        enableOnPass: Boolean = false,
        assertions: (StripeCardCheckActivity) -> Unit,
    ) {
        val intent = StripeCardCheckActivity.intent(
            ApplicationProvider.getApplicationContext(),
            enableOnPass = enableOnPass,
        )
        ActivityScenario.launch<StripeCardCheckActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.checkerFactory = { CardSetupChecker(environment) }
                activity.runCheck()
                shadowOf(activity.mainLooper).idle()
                assertions(activity)
            }
        }
    }

    private fun StripeCardCheckActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `a first full pass turns card payments on`() {
        runCheck(FakeEnvironment()) { activity ->
            assertEquals("Ready to take cards", activity.text(R.id.summary_title))
            assertEquals("Done", activity.text(R.id.primary_button))
            assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.secondary_button).visibility)
            assertTrue(settings.isEnabled)
            assertTrue(settings.hasPassedCheck)
        }
    }

    @Test
    fun `a failed check leaves card payments off and offers the fix`() {
        runCheck(FakeEnvironment(nfcEnabled = false)) { activity ->
            assertEquals("NFC is switched off", activity.text(R.id.summary_title))
            assertEquals("Open NFC settings", activity.text(R.id.primary_button))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.secondary_button).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.problem_dots).visibility)
            assertEquals(
                "Device: needs fixing",
                activity.findViewById<View>(R.id.step_phone).contentDescription.toString(),
            )
            assertFalse(settings.isEnabled)
            assertFalse(settings.hasPassedCheck)
        }
    }

    @Test
    fun `a rejected key is explained with its fix and the reader is not tried`() {
        val environment = FakeEnvironment(tokenError = StripeApiException("Invalid API Key", 401, null))
        runCheck(environment) { activity ->
            assertEquals("Stripe doesn't recognise your key", activity.text(R.id.summary_title))
            assertTrue(activity.text(R.id.summary_body).startsWith("Check that you copied all of it"))
            assertEquals("Replace key", activity.text(R.id.primary_button))
            assertEquals(
                "Reader: not checked yet",
                activity.findViewById<View>(R.id.step_reader).contentDescription.toString(),
            )
            assertEquals(0, environment.readerStarts)
        }
    }

    @Test
    fun `losing the connection offers one retry button, not two`() {
        runCheck(FakeEnvironment(tokenError = IOException("offline"))) { activity ->
            assertEquals("Couldn't reach Stripe", activity.text(R.id.summary_title))
            assertEquals("Try again", activity.text(R.id.primary_button))
            assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.secondary_button).visibility)
        }
    }

    @Test
    fun `a pass does not unpause card payments unless that was asked for`() {
        settings.hasPassedCheck = true
        settings.isEnabled = false

        runCheck(FakeEnvironment()) { activity ->
            assertFalse(settings.isEnabled)
            assertEquals("Everything works", activity.text(R.id.summary_title))
            assertEquals(
                "Card payments are still paused. Turn them on in Card payments.",
                activity.text(R.id.summary_body),
            )
        }
        runCheck(FakeEnvironment(), enableOnPass = true) {
            assertTrue(settings.isEnabled)
        }
    }
}
