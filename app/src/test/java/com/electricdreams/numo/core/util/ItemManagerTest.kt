package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class ItemManagerTest {

    @Test
    fun `legacy catalog migration preserves active ecash currency and gross price`() {
        CurrencyManager.getInstance(context).setPreferredCurrency("EUR")
        MintManager.getInstance(context).setPreferredUnit("usd")
        val legacy = JSONObject()
            .put("id", "legacy-usd").put("name", "Coffee")
            .put("price", Item.calculateNetFromGross(3.99, 20.0))
            .put("priceType", "FIAT").put("vatEnabled", true).put("vatRate", 20)
        context.getSharedPreferences("ItemManagerPrefs", Context.MODE_PRIVATE).edit()
            .putString("items_list", JSONArray().put(legacy).toString()).commit()
        resetSingleton()
        val migrated = ItemManager.getInstance(context).getAllItems().single()
        assertEquals("usd", migrated.priceUnit)
        assertEquals(399L, migrated.grossPriceAtomic)

        MintManager.getInstance(context).setPreferredUnit("eur")
        resetSingleton()
        val reloaded = ItemManager.getInstance(context).getAllItems().single()
        assertEquals("usd", reloaded.priceUnit)
        assertEquals(399L, reloaded.grossPriceAtomic)
    }

    private lateinit var context: Context
    private lateinit var itemManager: ItemManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset Singleton
        resetSingleton()
        
        // Ensure clean state
        val prefs = context.getSharedPreferences("ItemManagerPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        MintManager.getInstance(context).setPreferredUnit("sat")
        // Sat wallets use the local fiat currency for legacy catalog prices.
        CurrencyManager.getInstance(context).setPreferredCurrency(CurrencyManager.CURRENCY_USD)
        
        itemManager = ItemManager.getInstance(context)
    }

    private fun resetSingleton() {
        try {
            val field = ItemManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testAddItem() {
        val item = Item(name = "Test Item", price = 10.0)
        assertTrue(itemManager.addItem(item))
        
        val items = itemManager.getAllItems()
        assertEquals(1, items.size)
        assertEquals("Test Item", items[0].name)
        assertNotNull(items[0].id)
    }

    @Test
    fun testUpdateItem() {
        val item = Item(name = "Original Name", price = 10.0)
        itemManager.addItem(item)
        
        // Modify
        item.name = "Updated Name"
        assertTrue(itemManager.updateItem(item))
        
        val items = itemManager.getAllItems()
        assertEquals("Updated Name", items[0].name)
    }

    @Test
    fun testRemoveItem() {
        val item = Item(name = "To Remove", price = 10.0)
        itemManager.addItem(item)
        assertNotNull(item.id)
        
        assertTrue(itemManager.removeItem(item.id!!))
        assertTrue(itemManager.getAllItems().isEmpty())
    }
    
    @Test
    fun testSearchItems() {
        val item1 = Item(name = "Apple", category = "Fruit")
        val item2 = Item(name = "Banana", category = "Fruit")
        val item3 = Item(name = "Carrot", category = "Vegetable")
        
        itemManager.addItem(item1)
        itemManager.addItem(item2)
        itemManager.addItem(item3)
        
        val fruits = itemManager.searchItems("Fruit")
        assertEquals(2, fruits.size)
        
        val carrots = itemManager.searchItems("Carrot")
        assertEquals(1, carrots.size)
        
        val empty = itemManager.searchItems("Steak")
        assertTrue(empty.isEmpty())
    }

    @Test
    fun testDuplicateChecks() {
        val item1 = Item(name = "Item 1", gtin = "123456", sku = "SKU-1")
        itemManager.addItem(item1)
        
        assertTrue(itemManager.isGtinDuplicate("123456"))
        assertFalse(itemManager.isGtinDuplicate("999999"))
        
        // Check exclude own ID
        assertFalse(itemManager.isGtinDuplicate("123456", item1.id))
        
        assertTrue(itemManager.isSkuDuplicate("SKU-1"))
        assertFalse(itemManager.isSkuDuplicate("SKU-2"))
    }
    
    @Test
    fun testCategories() {
        itemManager.addItem(Item(name = "A", category = "Cat1"))
        itemManager.addItem(Item(name = "B", category = "Cat1"))
        itemManager.addItem(Item(name = "C", category = "Cat2"))
        
        val categories = itemManager.getAllCategories()
        assertEquals(2, categories.size)
        assertTrue(categories.contains("Cat1"))
        assertTrue(categories.contains("Cat2"))
    }
    
    @Test
    fun testPersistence() {
        val item = Item(
            name = "Persistent Item", 
            price = 99.99, 
            priceType = PriceType.FIAT,
            vatEnabled = true,
            vatRate = 20
        )
        itemManager.addItem(item)
        
        // Re-create manager to simulate app restart
        resetSingleton()
        val newManager = ItemManager.getInstance(context)
        
        val items = newManager.getAllItems()
        assertEquals(1, items.size)
        val loaded = items[0]
        
        assertEquals("Persistent Item", loaded.name)
        assertEquals(99.99, loaded.price, 0.001)
        assertTrue(loaded.vatEnabled)
        assertEquals(20, loaded.vatRate)
        assertEquals("usd", loaded.priceUnit)
        assertEquals(9_999L, loaded.priceAtomic)
    }

    @Test
    fun `custom unit price persists without reinterpretation`() {
        val item = Item(
            name = "Loyalty item",
            priceType = PriceType.FIAT,
            priceUnit = "POINTS",
            priceAtomic = 75L,
            priceIssuerScope = "https://mint.example",
        )
        itemManager.addItem(item)

        resetSingleton()
        val loaded = ItemManager.getInstance(context).getAllItems().single()

        assertEquals("points", loaded.priceUnit)
        assertEquals(75L, loaded.priceAtomic)
        assertEquals("https://mint.example", loaded.priceIssuerScope)
    }

    @Test
    fun `one corrupt persisted item does not hide valid catalog items`() {
        val corrupt = JSONObject()
            .put("id", "bad")
            .put("name", "Bad")
            .put("price", -1.0)
            .put("priceType", "FIAT")
        val valid = JSONObject()
            .put("id", "good")
            .put("name", "Good")
            .put("price", 2.5)
            .put("priceType", "FIAT")
        context.getSharedPreferences("ItemManagerPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("items_list", JSONArray().put(corrupt).put(valid).toString())
            .commit()

        resetSingleton()
        val loaded = ItemManager.getInstance(context).getAllItems()

        assertEquals(1, loaded.size)
        assertEquals("good", loaded.single().id)
        assertEquals(250L, loaded.single().priceAtomic)
    }
    
    @Test
    fun testCsvImport() {
        // Create a temporary CSV file
        val csvContent = """
            Token,Item Name,Variation Name,SKU,Description,Category,Option Name 1,GTIN,Option Value 1,Option Name 2,Option Value 2,Option Name 3,Price,Quantity,New Quantity,Stock Enabled,Stock Alert Enabled,Stock Alert Count,Tax Enabled,Tax Name,Quantity,Stock Enabled,Alert Enabled,Alert Threshold
            ,,,,,,,,,,,,,,,,,,,,,,,
            ,,,,,,,,,,,,,,,,,,,,,,,
            ,,,,,,,,,,,,,,,,,,,,,,,
            ,,,,,,,,,,,,,,,,,,,,,,,
            ,Imported Item,,SKU-IMP,Desc,ImpCat,,GTIN-IMP,,,,,15.50,,,,,,,,20,Y,Y,5
        """.trimIndent()
        
        val file = File(context.cacheDir, "test_import.csv")
        file.writeText(csvContent)
        
        val count = itemManager.importItemsFromCsv(file.absolutePath, true)
        assertEquals(1, count)
        
        val items = itemManager.getAllItems()
        assertEquals(1, items.size)
        assertEquals("Imported Item", items[0].name)
        assertEquals("SKU-IMP", items[0].sku)
        assertEquals(15.50, items[0].price, 0.001)
        assertEquals(20, items[0].quantity)
        assertTrue(items[0].alertEnabled)
        assertEquals(5, items[0].alertThreshold)
    }

    @Test
    fun testCsvExport() {
        // Add a test item
        val item = Item(
            name = "Exported Item",
            sku = "SKU-EXP",
            description = "Export Description",
            price = 25.75,
            priceType = PriceType.FIAT,
            quantity = 10,
            trackInventory = true,
            alertEnabled = true,
            alertThreshold = 2
        )
        itemManager.addItem(item)

        // Create an output stream to a temporary file
        val file = File(context.cacheDir, "test_export.csv")
        val outputStream = FileOutputStream(file)

        // Export to CSV
        val success = itemManager.exportItemsToCsv(outputStream)
        outputStream.close()

        assertTrue(success)
        assertTrue(file.exists())

        // Read the exported file and verify contents
        val lines = file.readLines()
        
        // 1 header + 4 empty rows + 1 data row = 6 lines
        assertEquals(6, lines.size)
        
        val dataRow = lines.last()
        // Format should be properly escaped with quotes where necessary
        assertTrue(dataRow.contains("Exported Item"))
        assertTrue(dataRow.contains("SKU-EXP"))
        assertTrue(dataRow.contains("Export Description"))
        assertTrue(dataRow.contains("25.75"))
        assertTrue(dataRow.contains("10")) // Quantity at index 20
        assertTrue(dataRow.contains("Y")) // Alert enabled at index 22
        assertTrue(dataRow.contains("2")) // Alert threshold at index 23
    }
}
