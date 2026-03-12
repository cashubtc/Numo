package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class ItemManagerRoundtripTest {

    private lateinit var context: Context
    private lateinit var itemManager: ItemManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        try {
            val field = ItemManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {}
        
        val prefs = context.getSharedPreferences("ItemManagerPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        
        itemManager = ItemManager.getInstance(context)
    }

    @Test
    fun testExportImportRoundtrip() {
        // Create an item
        val item = Item(
            name = "Roundtrip Item",
            sku = "SKU-RT",
            description = "Roundtrip Description",
            price = 42.50,
            priceType = PriceType.FIAT,
            quantity = 15,
            trackInventory = true,
            alertEnabled = true,
            alertThreshold = 3,
            category = "Test Category"
        )
        itemManager.addItem(item)
        
        val file = File(context.cacheDir, "test_roundtrip.csv")
        
        // Export
        val outputStream = FileOutputStream(file)
        val exportSuccess = itemManager.exportItemsToCsv(outputStream)
        outputStream.close()
        
        assertTrue("Export failed", exportSuccess)
        
        // Clear items to simulate another device
        itemManager.clearItems()
        assertEquals(0, itemManager.getAllItems().size)
        
        // Import
        val importedCount = itemManager.importItemsFromCsv(file.absolutePath, true)
        
        // Assert
        assertEquals("Should import 1 item", 1, importedCount)
        
        val importedItems = itemManager.getAllItems()
        assertEquals(1, importedItems.size)
        
        val importedItem = importedItems[0]
        assertEquals("Roundtrip Item", importedItem.name)
        assertEquals("SKU-RT", importedItem.sku)
        assertEquals("Roundtrip Description", importedItem.description)
        assertEquals("Test Category", importedItem.category)
        assertEquals(42.50, importedItem.price, 0.001)
        // Check if quantity and alerts are preserved
        assertEquals(15, importedItem.quantity)
        assertTrue(importedItem.alertEnabled)
        assertEquals(3, importedItem.alertThreshold)
    }
}
