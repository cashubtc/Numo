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
class ItemManagerRoundtripNumoTest {

    private lateinit var context: Context
    private lateinit var itemManager: ItemManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("ItemManagerPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        
        try {
            val field = ItemManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {}
        
        itemManager = ItemManager.getInstance(context)
    }

    @Test
    fun testSatsAndVatRoundtrip() {
        val item = Item(
            name = "Sats Item",
            price = 0.0,
            priceSats = 15000L,
            priceType = PriceType.SATS,
            vatEnabled = true,
            vatRate = 20,
            trackInventory = true,
            quantity = 50
        )
        itemManager.addItem(item)
        
        val file = File(context.cacheDir, "test_sats.csv")
        val outputStream = FileOutputStream(file)
        itemManager.exportItemsToCsv(outputStream)
        outputStream.close()
        
        itemManager.clearItems()
        
        val importedCount = itemManager.importItemsFromCsv(file.absolutePath, true)
        assertEquals(1, importedCount)
        
        val importedItems = itemManager.getAllItems()
        val importedItem = importedItems[0]
        
        assertEquals("Sats Item", importedItem.name)
        assertEquals(PriceType.SATS, importedItem.priceType)
        assertEquals(15000L, importedItem.priceSats)
        assertTrue(importedItem.vatEnabled)
        assertEquals(20, importedItem.vatRate)
        assertTrue(importedItem.trackInventory)
        assertEquals(50, importedItem.quantity)
    }
}
