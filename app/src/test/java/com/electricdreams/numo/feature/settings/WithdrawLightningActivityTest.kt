package com.electricdreams.numo.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WithdrawLightningActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        BitcoinPriceWorker.isTesting = true
        ReflectionHelpers.setStaticField(LightningAddressManager::class.java, "instance", null)
        LightningAddressManager.getInstance(context).clearLightningAddress()
    }

    @Test
    fun `saved lightning address is prefilled and asks for an amount`() {
        LightningAddressManager.getInstance(context).setLightningAddress("shop@wallet.com")

        launch { activity ->
            assertEquals("shop@wallet.com", input(activity).text.toString())
            assertEquals(activity.getString(R.string.withdraw_to_detected_address), toLayout(activity).helperText)
            assertEquals(View.VISIBLE, amountSection(activity).visibility)
        }
    }

    @Test
    fun `pasting a lightning link shows the invoice and its amount instead of an amount field`() {
        clipboard().setPrimaryClip(ClipData.newPlainText("invoice", "lightning:" + INVOICE.uppercase()))

        launch { activity ->
            activity.findViewById<Button>(R.id.paste_button).performClick()
            idle()

            assertEquals(INVOICE, input(activity).text.toString())
            assertEquals(
                activity.getString(
                    R.string.withdraw_to_detected_invoice_amount,
                    Amount(250_000L, Amount.Currency.BTC).toString()
                ),
                toLayout(activity).helperText
            )
            assertEquals(View.GONE, amountSection(activity).visibility)
        }
    }

    @Test
    fun `malformed destination is only flagged once editing is done`() {
        launch { activity ->
            input(activity).setText("shop@wallet")
            assertNull(toLayout(activity).error)

            input(activity).onEditorAction(EditorInfo.IME_ACTION_DONE)
            assertEquals(activity.getString(R.string.withdraw_to_invalid), toLayout(activity).error)

            input(activity).setText("shop@wallet.com")
            assertNull(toLayout(activity).error)
        }
    }

    @Test
    fun `end icon scans when empty and clears when filled`() {
        launch { activity ->
            assertEquals(activity.getString(R.string.withdraw_to_scan), toLayout(activity).endIconContentDescription)

            input(activity).setText("shop@wallet.com")
            assertEquals(activity.getString(R.string.withdraw_to_clear), toLayout(activity).endIconContentDescription)

            toLayout(activity).findViewById<View>(com.google.android.material.R.id.text_input_end_icon).performClick()
            assertEquals("", input(activity).text.toString())
        }
    }

    @Test
    fun `review stays disabled while there is nothing to send from`() {
        LightningAddressManager.getInstance(context).setLightningAddress("shop@wallet.com")

        launch { activity ->
            assertFalse(activity.findViewById<Button>(R.id.review_button).isEnabled)
        }
    }

    private fun launch(block: (WithdrawLightningActivity) -> Unit) {
        val intent = Intent(context, WithdrawLightningActivity::class.java)
        ActivityScenario.launch<WithdrawLightningActivity>(intent).use { scenario ->
            idle()
            scenario.onActivity(block)
        }
    }

    private fun input(activity: WithdrawLightningActivity): EditText = activity.findViewById(R.id.to_input)

    private fun toLayout(activity: WithdrawLightningActivity): TextInputLayout = activity.findViewById(R.id.to_layout)

    private fun amountSection(activity: WithdrawLightningActivity): View = activity.findViewById(R.id.amount)

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    companion object {
        // BOLT11 specification example: 2500u = 250,000 sat.
        private const val INVOICE =
            "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    }
}
