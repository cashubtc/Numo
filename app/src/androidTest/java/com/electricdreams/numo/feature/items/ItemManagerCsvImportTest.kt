package com.electricdreams.numo.feature.items

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.ItemManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test to verify CSV import for the merchant catalog.
 *
 * This test creates a temporary CSV file matching the expected Square export
 * format (with 5 header lines followed by item rows) and ensures that
 * [ItemManager.importItemsFromCsv] correctly parses and imports items
 * when the CSV format is respected.
 */
@RunWith(AndroidJUnit4::class)
class ItemManagerCsvImportTest {

    private lateinit var context: Context
    private lateinit var csvFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Prepare a temporary CSV file in the app's cache directory
        val cacheDir = context.cacheDir
        csvFile = File(cacheDir, "test_import_catalog.csv")

        // CSV content based on the expected Square template:
        // - First 5 lines are headers/metadata and will be skipped
        // - Item data starts on line 6
        // - Columns: we only rely on specific indices used in ItemManager
        //   0: token (ignored)
        //   1: name
        //   2: variation name
        //   3: SKU
        //   4: description
        //   5: category
        //   7: GTIN (barcode)
        //   12: price
        //   20: quantity
        //   22: stock alert enabled ("Y" / "N")
        //   23: stock alert threshold
        val csvContent = buildString {
            appendLine("HEADER LINE 1")
            appendLine("HEADER LINE 2")
            appendLine("HEADER LINE 3")
            appendLine("HEADER LINE 4")
            appendLine("HEADER LINE 5")

            // First item row
            appendLine(
                listOf(
                    "token-1",            // 0
                    "Coffee",             // 1 name
                    "Large",              // 2 variation
                    "SKU-COF-L",         // 3 sku
                    "Hot brewed coffee",  // 4 description
                    "Drinks",             // 5 category
                    "",                   // 6 unused
                    "1234567890123",      // 7 gtin
                    "",                   // 8-11 unused
                    "", "", "",
                    "3.50",               // 12 price
                    "", "", "", "", "", "", "", // 13-19 unused
                    "10",                 // 20 quantity
                    "",                   // 21 unused
                    "Y",                  // 22 alert enabled
                    "2"                   // 23 alert threshold
                ).joinToString(",")
            )

            // Second item row with minimal data and no alerts
            appendLine(
                listOf(
                    "token-2",            // 0
                    "Tea",                // 1 name
                    "",                   // 2 variation
                    "SKU-TEA",           // 3 sku
                    "Green tea",         // 4 description
                    "Drinks",             // 5 category
                    "",                   // 6 unused
                    "9876543210987",      // 7 gtin
                    "", "", "", "",   // 8-11 unused
                    "2.00",               // 12 price
                    "", "", "", "", "", "", "", // 13-19 unused
                    "5",                  // 20 quantity
                    "",                   // 21 unused
                    "N",                  // 22 alert enabled
                    "0"                   // 23 alert threshold
                ).joinToString(",")
            )
        }

        csvFile.writeText(csvContent)

        // Clear any existing items in storage before each run
        val manager = ItemManager.getInstance(context)
        manager.clearItems()
    }

    @After
    fun tearDown() {
        // Clean up temporary CSV file
        if (csvFile.exists()) {
            csvFile.delete()
        }

        // Also clear items to avoid side effects between tests
        val manager = ItemManager.getInstance(context)
        manager.clearItems()
    }

    @Test
    fun importItemsFromValidCsv_importsAllFieldsCorrectly() {
        val manager = ItemManager.getInstance(context)

        val importedCount = manager.importItemsFromCsv(csvFile.absolutePath, clearExisting = true)
        val allItems: List<Item> = manager.getAllItems()

        // We expect two items imported from the CSV
        assertEquals(2, importedCount)
        assertEquals(2, allItems.size)

        val first = allItems[0]
        assertEquals("Coffee", first.name)
        assertEquals("Large", first.variationName)
        assertEquals("SKU-COF-L", first.sku)
        assertEquals("Hot brewed coffee", first.description)
        assertEquals("Drinks", first.category)
        assertEquals("1234567890123", first.gtin)
        assertEquals(3.50, first.price, 0.0001)
        assertEquals(10, first.quantity)
        assertEquals(true, first.alertEnabled)
        assertEquals(2, first.alertThreshold)

        val second = allItems[1]
        assertEquals("Tea", second.name)
        assertEquals("", second.variationName ?: "")
        assertEquals("SKU-TEA", second.sku)
        assertEquals("Green tea", second.description)
        assertEquals("Drinks", second.category)
        assertEquals("9876543210987", second.gtin)
        assertEquals(2.00, second.price, 0.0001)
        assertEquals(5, second.quantity)
        assertEquals(false, second.alertEnabled)
        assertEquals(0, second.alertThreshold)
    }
}
