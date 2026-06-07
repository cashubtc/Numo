package com.electricdreams.numo.feature.history

import android.content.Context
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.CheckoutBasketItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ActivityCsvExportHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clear history before each test
        val prefs = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `export to CSV includes Items column with item names and quantities`() {
        // Create checkout items
        val item1 = CheckoutBasketItem(
            itemId = "item_a",
            uuid = "uuid_a",
            name = "Item A",
            variationName = null,
            sku = "sku_a",
            category = "cat_a",
            quantity = 2,
            priceType = "FIAT",
            netPriceCents = 100L,
            priceSats = 0L,
            priceCurrency = "USD",
            vatEnabled = false,
            vatRate = 0
        )
        
        val item2 = CheckoutBasketItem(
            itemId = "item_b",
            uuid = "uuid_b",
            name = "Item B",
            variationName = "Large",
            sku = "sku_b",
            category = "cat_b",
            quantity = 1,
            priceType = "FIAT",
            netPriceCents = 200L,
            priceSats = 0L,
            priceCurrency = "USD",
            vatEnabled = false,
            vatRate = 0
        )

        // Construct CheckoutBasket
        val basket = CheckoutBasket(
            id = "basket_id",
            checkoutTimestamp = System.currentTimeMillis(),
            items = listOf(item1, item2),
            currency = "USD",
            bitcoinPrice = 60000.0,
            totalSatoshis = 500L
        )

        // Add payment history entry with the checkout basket JSON
        PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 500L,
            entryUnit = "sat",
            enteredAmount = 500L,
            bitcoinPrice = 60000.0,
            paymentRequest = "lnbc...",
            formattedAmount = "₿0.00000500",
            checkoutBasketJson = basket.toJson()
        )

        // Add a payment entry without a checkout basket
        PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 300L,
            entryUnit = "sat",
            enteredAmount = 300L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = null,
            checkoutBasketJson = null
        )

        val outputStream = ByteArrayOutputStream()
        val success = ActivityCsvExportHelper.exportActivityToCsv(context, outputStream)
        assertTrue(success)

        val csvOutput = outputStream.toString("UTF-8")
        assertNotNull(csvOutput)

        // Split CSV into lines
        val lines = csvOutput.trim().split("\n")
        assertTrue(lines.size >= 3) // Header + 2 entries

        // Verify headers
        val headers = lines[0].split(",")
        assertTrue(headers.contains("Items"))
        
        // Find index of "Items" column in the headers
        val itemsIndex = headers.indexOf("Items")
        val transactionIdIndex = headers.indexOf("Transaction ID")
        assertEquals(itemsIndex + 1, transactionIdIndex) // "Items" is right before "Transaction ID"

        // Verify entries
        val expectedItemsStr = "Item A (x2); Item B - Large (x1)"
        assertTrue("CSV output should contain the formatted items list", csvOutput.contains(expectedItemsStr))
    }
}
