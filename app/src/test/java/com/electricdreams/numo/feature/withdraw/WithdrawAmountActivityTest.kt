package com.electricdreams.numo.feature.withdraw

import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WithdrawAmountActivityTest {

    private val mintUrl = "https://test.mint.com"
    private val balance = 1000L

    private fun launchIntent(withExtras: Boolean = true): Intent {
        return Intent(ApplicationProvider.getApplicationContext(), WithdrawAmountActivity::class.java).apply {
            if (withExtras) {
                putExtra(WithdrawAmountActivity.EXTRA_MINT_URL, mintUrl)
                putExtra(WithdrawAmountActivity.EXTRA_BALANCE, balance)
                putExtra(WithdrawAmountActivity.EXTRA_LIGHTNING_ADDRESS, "user@getalby.com")
            }
        }
    }

    @Test
    fun `finishes when launched without extras`() {
        ActivityScenario.launch<WithdrawAmountActivity>(launchIntent(withExtras = false)).use { scenario ->
            // The activity guard-finishes in onCreate, so by the time the
            // scenario settles it must be on its way out (or already gone).
            assertTrue(
                "Activity should finish without mint/address extras, state was ${scenario.state}",
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
            )
        }
    }

    @Test
    fun `continue starts gated at zero amount`() {
        ActivityScenario.launch<WithdrawAmountActivity>(launchIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val continueButton = activity.findViewById<Button>(R.id.continue_button)
                assertEquals("Continue should look disabled at 0", 0.5f, continueButton.alpha)
            }
        }
    }

    @Test
    fun `destination and max chip reflect extras`() {
        ActivityScenario.launch<WithdrawAmountActivity>(launchIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val destination = activity.findViewById<TextView>(R.id.destination_text)
                assertTrue(destination.text.toString().contains("user@getalby.com"))

                // MAX = balance minus the 2% fee buffer = 980 sats
                val maxChip = activity.findViewById<TextView>(R.id.max_chip)
                assertTrue(
                    "MAX chip should show the fee-buffered balance, was: ${maxChip.text}",
                    maxChip.text.toString().contains("980")
                )
            }
        }
    }
}
