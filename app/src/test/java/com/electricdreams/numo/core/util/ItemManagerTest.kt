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
import java.io.File
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class ItemManagerTest {

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
}
