package com.electricdreams.numo.feature.items.handlers

import android.app.Activity
import android.app.Application
import androidx.appcompat.app.AlertDialog
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.UnitId
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.tips.TipSelectionActivity
import com.electricdreams.numo.feature.tips.TipsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CheckoutHandlerTest {
    private lateinit var activity: Activity
    private lateinit var basket: BasketManager
    private lateinit var handler: CheckoutHandler

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Numo)
        val mints = MintManager.getInstance(activity)
        mints.setMintChangeListener(null)
        mints.getAllowedMints().forEach { mints.removeMint(it) }
        mints.addMint("https://mint.example")
        mints.setMintUnits("https://mint.example", listOf("sat", "usd"))
        mints.setPreferredUnit("sat")
        val currency = CurrencyManager.getInstance(activity)
        currency.setPreferredCurrency("USD")
        val priceWorker = mock<BitcoinPriceWorker>()
        whenever(priceWorker.getCurrentPrice()).thenReturn(100_000.0)
        whenever(priceWorker.getCurrentPriceTimestamp()).thenReturn(System.currentTimeMillis())
        basket = BasketManager.getInstance()
        basket.clearBasket()
        basket.addItem(Item(id = "coffee", priceUnit = "usd", priceAtomic = 100), 1)
        handler = CheckoutHandler(activity, basket, currency, priceWorker)
        handler.savedBasketId = "saved-basket"
    }

    @Test
    fun `visible currency choices launch payment with selected unit and basket snapshot`() {
        selectDollarCharge(tipsEnabled = false)
    }

    @Test
    fun `visible currency choices preserve the selected unit when routing through tips`() {
        selectDollarCharge(tipsEnabled = true)
    }

    private fun selectDollarCharge(tipsEnabled: Boolean) {
        TipsManager.getInstance(activity).tipsEnabled = tipsEnabled
        handler.proceedToCheckout()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(dialog.listView.parent)
        assertEquals(2, dialog.listView.adapter.count)
        assertEquals(1, basket.getTotalItemCount())
        dialog.listView.performItemClick(null, 1, 1)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(
            if (tipsEnabled) TipSelectionActivity::class.java.name else PaymentRequestActivity::class.java.name,
            intent.component?.className,
        )
        assertEquals("usd", intent.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_UNIT))
        assertEquals(100L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, 0))
        assertEquals("saved-basket", intent.getStringExtra(PaymentRequestActivity.EXTRA_SAVED_BASKET_ID))
        val snapshot = requireNotNull(CheckoutBasket.fromJson(
            intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON),
        ))
        assertEquals(UnitId.of("usd"), snapshot.getChargeAmount().unit)
        assertEquals(100L, snapshot.getChargeAmount().value)
        assertEquals(0, basket.getTotalItemCount())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `cancelling the unit choice preserves the basket`() {
        handler.proceedToCheckout()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(1, basket.getTotalItemCount())
        assertFalse(activity.isFinishing)
    }
}
