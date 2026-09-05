package com.electricdreams.numo.feature.history

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import com.electricdreams.numo.core.model.UnitAmountFormatter
import com.electricdreams.numo.core.model.UnitDescriptor
import com.electricdreams.numo.core.model.UnitId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReceiptUnitIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun legacyYenBasket(): CheckoutBasket = CheckoutBasket(
        items = listOf(CheckoutBasketItem(
            itemId = "coffee", uuid = "coffee", name = "Coffee", quantity = 1,
            priceType = "FIAT", netPriceCents = 100_000, priceSats = 0,
            priceCurrency = "JPY", vatEnabled = false, vatRate = 0,
        )),
        currency = "JPY",
        totalSatoshis = 1_000,
    )

    @Test
    fun `receipt screen honors a legacy payment unit when the basket has none`() {
        val intent = Intent(context, BasketReceiptActivity::class.java)
            .putExtra(BasketReceiptActivity.EXTRA_CHECKOUT_BASKET_JSON, legacyYenBasket().toJson())
            .putExtra(BasketReceiptActivity.EXTRA_PAYMENT_UNIT, "jpy")
            .putExtra(BasketReceiptActivity.EXTRA_TOTAL_SATOSHIS, 1_000L)
        val controller = Robolectric.buildActivity(BasketReceiptActivity::class.java, intent).setup()
        try {
            val expected = UnitAmountFormatter.formatAtomic(
                1_000, UnitDescriptor.defaultFor(UnitId.of("jpy")),
            )
            val paidAmount = controller.get().findViewById<TextView>(R.id.paid_amount)
            assertEquals(expected, paidAmount.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `transaction detail keeps legacy yen entry amounts in hundredths`() {
        val intent = Intent(context, TransactionDetailActivity::class.java)
            .putExtra(TransactionDetailActivity.EXTRA_CHECKOUT_BASKET_JSON, legacyYenBasket().toJson())
            .putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_UNIT, "sat")
            .putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_AMOUNT, 10_000L)
            .putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTRY_UNIT, "JPY")
            .putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTERED_AMOUNT, 100_000L)
        val controller = Robolectric.buildActivity(TransactionDetailActivity::class.java, intent)
            .setup()
        try {
            val equivalent = controller.get().findViewById<TextView>(R.id.sats_equivalent)
            assertEquals("≈ " + Amount(100_000, Amount.Currency.JPY), equivalent.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
