package com.electricdreams.numo.feature.history

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.components.ConfirmationBottomSheet
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TransactionDetailActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a sale keyed in dollars totals what was charged, not its sats`() {
        // 2 × Cinnamon Bun at $4.25, paid as 10,109 sats
        val sale = sale(enteredCents = 850, sats = 10_109, basket = cinnamonBuns(quantity = 2))

        ActivityScenario.launch<TransactionDetailActivity>(
            PaymentIntentFactory.createTransactionDetailIntent(context, sale),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val headline = activity.findViewById<TextView>(R.id.detail_amount).text.toString()
                val total = activity.findViewById<TextView>(R.id.final_total_value).text.toString()
                assertEquals("$8.50", headline)
                assertEquals(headline, total)
            }
        }
    }

    @Test
    fun `a basket with nothing itemised shows its total without an empty band above it`() {
        val sale = sale(enteredCents = 850, sats = 10_109, basket = cinnamonBuns(quantity = 2))

        ActivityScenario.launch<TransactionDetailActivity>(
            PaymentIntentFactory.createTransactionDetailIntent(context, sale),
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.totals_divider).visibility)
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.vat_breakdown_container).visibility,
                )
            }
        }
    }

    @Test
    fun `a tip shows on its own row, apart from the sale`() {
        // $8.50 sale plus a 1,000 sat (12%) tip, paid at $80,000
        val sale = sale(enteredCents = 850, sats = 11_000, basket = null, tipSats = 1_000, tipPercent = 12)

        ActivityScenario.launch<TransactionDetailActivity>(
            PaymentIntentFactory.createTransactionDetailIntent(context, sale),
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("$8.50", activity.findViewById<TextView>(R.id.detail_amount).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.row_tip).visibility)
                assertEquals("$0.80 · 12%", activity.findViewById<TextView>(R.id.detail_tip).text.toString())
            }
        }
    }

    @Test
    fun `an expired payment says so and offers no receipt`() {
        val expired = sale(
            enteredCents = 1_225, sats = 14_570, basket = null,
            status = PaymentHistoryEntry.STATUS_EXPIRED,
        )

        ActivityScenario.launch<TransactionDetailActivity>(
            PaymentIntentFactory.createTransactionDetailIntent(context, expired),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.detail_status)
                assertEquals(View.VISIBLE, status.visibility)
                assertEquals(activity.getString(R.string.history_row_status_expired), status.text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_print_receipt).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.row_tip).visibility)
                // It was never received, so it has no payment type to name
                assertEquals(View.GONE, activity.findViewById<View>(R.id.row_transaction_type).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.row_mint).visibility)
            }
        }
    }

    @Test
    fun `deleting a sale removes it from history wherever details were opened from`() {
        val sale = sale(enteredCents = 850, sats = 10_109, basket = cinnamonBuns(quantity = 2))
        val kept = sale(enteredCents = 375, sats = 4_460, basket = null)
        saveHistory(sale, kept)

        // Opened as Sales, the payment screen and the basket archive open it: no result is read
        ActivityScenario.launch<TransactionDetailActivity>(
            PaymentIntentFactory.createTransactionDetailIntent(context, sale),
        ).use { scenario ->
            // The overflow's Delete item; Robolectric can't reach into an AppCompat PopupMenu
            scenario.onActivity { activity ->
                TransactionDetailActivity::class.java.getDeclaredMethod("showDeleteConfirmation")
                    .apply { isAccessible = true }
                    .invoke(activity)
            }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { activity ->
                confirmationSheet(activity).requireView()
                    .findViewById<View>(R.id.confirm_button).performClick()
                assertTrue(activity.isFinishing)
            }
        }

        assertEquals(
            listOf(kept.id),
            PaymentsHistoryActivity.getPaymentHistory(context).map { it.id },
        )
    }

    private fun confirmationSheet(activity: AppCompatActivity): ConfirmationBottomSheet =
        activity.supportFragmentManager.fragments.filterIsInstance<ConfirmationBottomSheet>().single()

    private fun sale(
        enteredCents: Long,
        sats: Long,
        basket: CheckoutBasket?,
        tipSats: Long = 0,
        tipPercent: Int = 0,
        status: String = PaymentHistoryEntry.STATUS_COMPLETED,
    ) = PaymentHistoryEntry(
        token = "",
        amount = sats,
        date = Date(),
        rawUnit = "sat",
        rawEntryUnit = "USD",
        enteredAmount = enteredCents,
        bitcoinPrice = if (tipSats > 0) 80_000.0 else 84_079.72,
        // A payment only learns its mint and type once it settles
        mintUrl = if (status == PaymentHistoryEntry.STATUS_COMPLETED) "https://mint.example.com" else null,
        paymentRequest = null,
        rawStatus = status,
        paymentType = if (status == PaymentHistoryEntry.STATUS_COMPLETED) PaymentHistoryEntry.TYPE_CASHU else null,
        checkoutBasketJson = basket?.toJson(),
        tipAmountSats = tipSats,
        tipPercentage = tipPercent,
    )

    private fun cinnamonBuns(quantity: Int) = CheckoutBasket(
        items = listOf(
            CheckoutBasketItem(
                itemId = "bun",
                uuid = "bun-uuid",
                name = "Cinnamon Bun",
                quantity = quantity,
                priceType = "FIAT",
                netPriceCents = 425,
                priceSats = 0,
                priceCurrency = "USD",
                vatEnabled = false,
                vatRate = 0,
            ),
        ),
        currency = "USD",
        bitcoinPrice = 84_079.72,
        totalSatoshis = 10_109,
    )

    private fun saveHistory(vararg entries: PaymentHistoryEntry) {
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit()
            .putString("history", Gson().toJson(entries.toList()))
            .commit()
    }
}
