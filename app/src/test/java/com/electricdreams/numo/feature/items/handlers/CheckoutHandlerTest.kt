package com.electricdreams.numo.feature.items.handlers

import android.app.Activity
import android.app.Application
import android.widget.TextView
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
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowToast

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
        assertEquals(activity.getString(R.string.pos_charge_unit_dialog_title),
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString())
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

    @Test
    fun `single custom charge option proceeds directly to tips`() {
        verifyImpliedCustomCharge(tipsEnabled = true)
    }

    @Test
    fun `single custom charge option proceeds directly to payment when tips are disabled`() {
        verifyImpliedCustomCharge(tipsEnabled = false)
    }

    private fun verifyImpliedCustomCharge(tipsEnabled: Boolean) {
        val item = prepareCustomBasket()
        TipsManager.getInstance(activity).tipsEnabled = tipsEnabled

        handler.proceedToCheckout()

        assertNull(ShadowDialog.getLatestDialog())
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(
            if (tipsEnabled) TipSelectionActivity::class.java.name else PaymentRequestActivity::class.java.name,
            intent.component?.className,
        )
        assertEquals("bux", intent.getStringExtra(PaymentRequestActivity.EXTRA_PAYMENT_UNIT))
        assertEquals(2500L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, 0))
        assertEquals("https://mint.example", intent.getStringExtra(
            PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE,
        ))
        val snapshot = requireNotNull(CheckoutBasket.fromJson(
            intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON),
        ))
        assertEquals(snapshot.getChargeAmount(), snapshot.items.single().getGrossLineAtomicAmount())
        assertNull("Resolving a checkout mint must not rewrite the catalog item", item.priceIssuerScope)
        assertEquals(2500L, item.priceAtomic)
        assertEquals(0, basket.getTotalItemCount())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `basket entirely priced in a nonconvertible unit skips the charge choice`() {
        prepareCustomBasket()
        basket.addItem(Item(id = "other", priceUnit = "bux", priceAtomic = 50), 2)
        // Other accepted units do not create a choice without a conversion quote.
        MintManager.getInstance(activity).setMintUnits("https://mint.example", listOf("bux", "sat", "usd"))

        handler.proceedToCheckout()

        assertNull(ShadowDialog.getLatestDialog())
        val intent = shadowOf(activity).nextStartedActivity
        val snapshot = requireNotNull(CheckoutBasket.fromJson(
            intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON),
        ))
        assertEquals("bux", snapshot.getChargeAmount().unit.value)
        assertEquals(2600L, snapshot.getChargeAmount().value)
        assertEquals(2, snapshot.items.size)
        assertTrue(snapshot.items.all { it.priceIssuerScope == "https://mint.example" })
    }

    @Test
    fun `same custom unit from two mints requires selection and records the chosen issuer`() {
        prepareCustomBasket()
        val mints = MintManager.getInstance(activity)
        mints.addMint("https://second.example")
        mints.setMintUnits("https://second.example", listOf("bux"))
        TipsManager.getInstance(activity).tipsEnabled = false

        handler.proceedToCheckout()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(2, dialog.listView.adapter.count)
        assertEquals(activity.getString(R.string.mint_selection_title),
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString())
        assertTrue(dialog.listView.adapter.getItem(1).toString().contains("second.example"))
        dialog.listView.performItemClick(null, 1, 1)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(PaymentRequestActivity::class.java.name, intent.component?.className)
        assertEquals("https://second.example", intent.getStringExtra(
            PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE,
        ))
        val snapshot = requireNotNull(CheckoutBasket.fromJson(
            intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON),
        ))
        assertEquals("https://second.example", snapshot.items.single().priceIssuerScope)
    }

    @Test
    fun `cancelling custom issuer choice leaves the basket and its price unchanged`() {
        val item = prepareCustomBasket()
        val mints = MintManager.getInstance(activity)
        mints.addMint("https://second.example")
        mints.setMintUnits("https://second.example", listOf("bux"))
        handler.proceedToCheckout()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()

        assertEquals(1, basket.getTotalItemCount())
        assertEquals(2500L, basket.getPriceLines("USD").single().value)
        assertNull(item.priceIssuerScope)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `scoped custom item never falls back to another mint with the same unit`() {
        prepareCustomBasket(issuer = "https://unavailable.example")
        handler.proceedToCheckout()
        assertCheckoutUnavailable()
    }

    @Test
    fun `custom prices from different issuers cannot be combined`() {
        prepareCustomBasket(issuer = "https://mint.example")
        val mints = MintManager.getInstance(activity)
        mints.addMint("https://second.example")
        mints.setMintUnits("https://second.example", listOf("bux"))
        basket.addItem(Item(
            id = "other", priceUnit = "bux", priceAtomic = 50,
            priceIssuerScope = "https://second.example",
        ), 1)
        handler.proceedToCheckout()
        assertCheckoutUnavailable(expectedCount = 2)
    }

    @Test
    fun `a mixed fiat and custom basket still requires an actual conversion quote`() {
        prepareCustomBasket()
        basket.addItem(Item(id = "dollars", priceUnit = "usd", priceAtomic = 100), 1)
        handler.proceedToCheckout()
        assertCheckoutUnavailable(expectedCount = 2)
    }

    @Test
    fun `unscoped custom item requires a mint supporting that exact unit`() {
        prepareCustomBasket()
        MintManager.getInstance(activity).setMintUnits("https://mint.example", listOf("points"))
        handler.proceedToCheckout()
        assertCheckoutUnavailable()
    }

    @Test
    fun `already scoped custom price checks out without an additional choice`() {
        prepareCustomBasket(issuer = "https://mint.example")
        handler.proceedToCheckout()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("https://mint.example", intent.getStringExtra(
            PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE,
        ))
        assertEquals(2500L, intent.getLongExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, 0))
    }

    private fun prepareCustomBasket(issuer: String? = null): Item {
        MintManager.getInstance(activity).setMintUnits("https://mint.example", listOf("bux"))
        basket.clearBasket()
        val item = Item(
            id = "espresso", name = "Espresso", priceUnit = "bux", priceAtomic = 2500,
            priceIssuerScope = issuer,
        )
        basket.addItem(item, 1)
        return item
    }

    @Test
    fun `assigning a custom issuer preserves VAT rounding and quantity in the receipt`() {
        val item = prepareCustomBasket().apply {
            priceAtomic = 101
            vatEnabled = true
            vatRate = 20
            grossPriceAtomic = 122
        }
        basket.updateItemQuantity("espresso", 3)
        handler.proceedToCheckout()
        assertNull(ShadowDialog.getLatestDialog())

        val intent = shadowOf(activity).nextStartedActivity
        val snapshot = requireNotNull(CheckoutBasket.fromJson(
            intent.getStringExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON),
        ))
        val line = snapshot.items.single()
        assertEquals(101L, line.getNetAtomicAmount().value)
        assertEquals(122L, line.getGrossAtomicAmount().value)
        assertEquals(3, line.quantity)
        assertEquals(366L, snapshot.getChargeAmount().value)
        assertEquals(snapshot.getChargeAmount(), line.getGrossLineAtomicAmount())
        assertNull(item.priceIssuerScope)
    }

    @Test
    fun `assigning an issuer still reports overflow instead of conversion unavailable`() {
        prepareCustomBasket().priceAtomic = Long.MAX_VALUE
        basket.addItem(Item(id = "extra", priceUnit = "bux", priceAtomic = 1), 1)
        handler.proceedToCheckout()

        assertEquals(activity.getString(R.string.checkout_unit_amount_too_large),
            ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(2, basket.getTotalItemCount())
    }

    private fun assertCheckoutUnavailable(expectedCount: Int = 1) {
        assertEquals(activity.getString(R.string.checkout_unit_conversion_unavailable),
            ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(expectedCount, basket.getTotalItemCount())
        assertFalse(activity.isFinishing)
    }
}
