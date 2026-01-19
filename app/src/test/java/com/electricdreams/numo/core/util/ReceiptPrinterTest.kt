package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.model.BasketItem
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class ReceiptPrinterTest {

    private lateinit var context: Context
    private lateinit var printer: ReceiptPrinter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        printer = ReceiptPrinter(context)
    }

    @Test
    fun testGenerateTextReceipt_FiatOnly() {
        val item1 = Item(name = "Coffee", price = 3.50, priceType = PriceType.FIAT)
        val item2 = Item(name = "Muffin", price = 2.50, priceType = PriceType.FIAT)
        
        val basketItem1 = BasketItem(item = item1, quantity = 2)
        val basketItem2 = BasketItem(item = item2, quantity = 1)
        
        // Use full path to avoid import issues if any
        val checkoutItem1 = com.electricdreams.numo.core.model.CheckoutBasketItem.fromBasketItem(basketItem1, "USD")
        val checkoutItem2 = com.electricdreams.numo.core.model.CheckoutBasketItem.fromBasketItem(basketItem2, "USD")
        
        val basket = CheckoutBasket(
            items = listOf(checkoutItem1, checkoutItem2),
            currency = "USD",
            totalSatoshis = 19000
        )
        
        val data = ReceiptPrinter.ReceiptData(
            basket = basket,
            paymentType = "lightning",
            paymentDate = Date(),
            transactionId = "tx123",
            mintUrl = "https://mint.test",
            bitcoinPrice = 50000.0,
            totalSatoshis = 19000,
            enteredAmount = 950
        )
        
        val receipt = printer.generateTextReceipt(data)
        
        assertTrue(receipt.contains("NUMO POS"))
        assertTrue(receipt.contains("RECEIPT"))
        assertTrue(receipt.contains("Coffee"))
        assertTrue(receipt.contains("2 x $3.50"))
        assertTrue(receipt.contains("Muffin"))
        assertTrue(receipt.contains("TOTAL:"))
        assertTrue(receipt.contains("$9.50"))
        assertTrue(receipt.contains("⚡ Lightning Network"))
    }

    @Test
    fun testGenerateTextReceipt_SatsOnly() {
        val item = Item(name = "Sats Item", priceSats = 1000, priceType = PriceType.SATS)
        val basketItem = BasketItem(item = item, quantity = 2)
        
        val checkoutItem = com.electricdreams.numo.core.model.CheckoutBasketItem.fromBasketItem(basketItem, "USD")
        
        val basket = CheckoutBasket(
            items = listOf(checkoutItem),
            currency = "USD",
            totalSatoshis = 2000
        )
        
        val data = ReceiptPrinter.ReceiptData(
            basket = basket,
            paymentType = "cashu",
            paymentDate = Date(),
            transactionId = "tx456",
            mintUrl = null,
            bitcoinPrice = 50000.0,
            totalSatoshis = 2000
        )
        
        val receipt = printer.generateTextReceipt(data)
        
        assertTrue(receipt.contains("Sats Item"))
        assertTrue(receipt.contains("2 x ₿1,000"))
        assertTrue(receipt.contains("TOTAL:"))
        assertTrue(receipt.contains("₿2,000"))
        assertTrue(receipt.contains("Cashu"))
    }

    @Test
    fun testGenerateTextReceipt_Mixed() {
        val fiatItem = Item(name = "Fiat", price = 1.00, priceType = PriceType.FIAT)
        val satsItem = Item(name = "Sats", priceSats = 2000, priceType = PriceType.SATS)
        
        val basketItem1 = BasketItem(item = fiatItem, quantity = 1)
        val basketItem2 = BasketItem(item = satsItem, quantity = 1)
        
        val checkoutItem1 = com.electricdreams.numo.core.model.CheckoutBasketItem.fromBasketItem(basketItem1, "USD")
        val checkoutItem2 = com.electricdreams.numo.core.model.CheckoutBasketItem.fromBasketItem(basketItem2, "USD")
        
        val basket = CheckoutBasket(
            items = listOf(checkoutItem1, checkoutItem2),
            currency = "USD",
            totalSatoshis = 4000
        )
        
        val data = ReceiptPrinter.ReceiptData(
            basket = basket,
            paymentType = "lightning",
            paymentDate = Date(),
            transactionId = "txMixed",
            mintUrl = null,
            bitcoinPrice = 50000.0,
            totalSatoshis = 4000
        )
        
        val receipt = printer.generateTextReceipt(data)
        
        assertTrue(receipt.contains("Fiat"))
        assertTrue(receipt.contains("Sats"))
        assertTrue(receipt.contains("TOTAL:"))
        assertTrue(receipt.contains("₿4,000"))
        assertTrue(receipt.contains("equivalent"))
    }

    @Test
    fun testHtmlGeneration() {
        val item = Item(name = "HTML Item", price = 5.00)
        val basketItem = BasketItem(item = item, quantity = 1)
        val checkoutItem = com.electricdreams.numo.core.model.CheckoutBasketItem.fromBasketItem(basketItem, "EUR")
        
        val basket = CheckoutBasket(
            items = listOf(checkoutItem),
            currency = "EUR",
            totalSatoshis = 10000
        )
        
        val data = ReceiptPrinter.ReceiptData(
            basket = basket,
            paymentType = "lightning",
            paymentDate = Date(),
            transactionId = "txHtml",
            mintUrl = "https://mint.test",
            bitcoinPrice = 50000.0,
            totalSatoshis = 10000
        )
        
        val html = printer.generateHtmlReceipt(data)
        
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("HTML Item"))
        assertTrue(html.contains("€5,00"))
        assertTrue(html.contains("NUMO POS"))
    }
}
