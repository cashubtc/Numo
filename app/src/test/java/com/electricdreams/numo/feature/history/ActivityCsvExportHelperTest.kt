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

    private fun splitCsvLine(line: String): List<String> {
        return line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clear history before each test
        val prefs = context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        // Reset MintPreferences to default state (including preferredUnit = "sat")
        val mintPrefs = context.getSharedPreferences("MintPreferences", Context.MODE_PRIVATE)
        mintPrefs.edit().clear().apply()
        com.electricdreams.numo.core.util.MintManager.getInstance(context).setPreferredUnit("sat")
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
        val headers = splitCsvLine(lines[0])
        assertEquals(15, headers.size)
        assertTrue(headers.contains("Items"))
        
        // Find index of "Items" column in the headers
        val itemsIndex = headers.indexOf("Items")
        val transactionIdIndex = headers.indexOf("Transaction ID")
        assertEquals(itemsIndex + 1, transactionIdIndex) // "Items" is right before "Transaction ID"

        // Verify that all rows have exactly 15 columns
        for (line in lines) {
            assertEquals(15, splitCsvLine(line).size)
        }

        // Verify that the Unit column contains "sat" and Amount (Formatted) contains "sat" representation
        val unitIndex = headers.indexOf("Unit")
        val formattedAmountIndex = headers.indexOf("Amount (Formatted)")
        
        val row1 = splitCsvLine(lines[1])
        val row2 = splitCsvLine(lines[2])
        
        assertEquals("sat", row1[unitIndex])
        assertEquals("sat", row2[unitIndex])
        
        // One should have 500 sat and other 300 sat
        val formattedAmounts = setOf(row1[formattedAmountIndex], row2[formattedAmountIndex])
        assertTrue(formattedAmounts.contains("500 sat"))
        assertTrue(formattedAmounts.contains("300 sat"))

        // Verify entries
        val expectedItemsStr = "Item A (x2); Item B - Large (x1)"
        assertTrue("CSV output should contain the formatted items list", csvOutput.contains(expectedItemsStr))
    }

    @Test
    fun `export to CSV supports custom units and formats correctly`() {
        // Set preferred unit to points
        com.electricdreams.numo.core.util.MintManager.getInstance(context).setPreferredUnit("points")

        // Add custom unit payment history entry
        PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 15L, // 15 points
            entryUnit = "points",
            enteredAmount = 15L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = "15 POINTS",
        )

        // Set preferred unit to USD
        com.electricdreams.numo.core.util.MintManager.getInstance(context).setPreferredUnit("usd")

        // Add custom unit payment history entry in USD cents
        PaymentsHistoryActivity.addPendingPayment(
            context = context,
            amount = 150L, // $1.50
            entryUnit = "usd",
            enteredAmount = 150L,
            bitcoinPrice = null,
            paymentRequest = null,
            formattedAmount = "$1.50",
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
        val headers = splitCsvLine(lines[0])
        assertEquals(15, headers.size)
        val unitIndex = headers.indexOf("Unit")
        val formattedAmountIndex = headers.indexOf("Amount (Formatted)")

        assertEquals(5, unitIndex)
        assertEquals(6, formattedAmountIndex)

        // Verify that all rows have exactly 15 columns
        for (line in lines) {
            assertEquals(15, splitCsvLine(line).size)
        }

        val row1 = splitCsvLine(lines[1])
        val row2 = splitCsvLine(lines[2])

        val pointsRow = if (row1[unitIndex] == "points") row1 else row2
        val usdRow = if (row1[unitIndex] == "usd") row1 else row2

        assertEquals("points", pointsRow[unitIndex])
        assertEquals("15 POINTS", pointsRow[formattedAmountIndex])

        assertEquals("usd", usdRow[unitIndex])
        // Depending on formatting in test locale (should format with $ and 2 decimals)
        assertEquals("$1.50", usdRow[formattedAmountIndex])
    }
}
