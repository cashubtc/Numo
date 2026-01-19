package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.model.BasketItem
import com.electricdreams.numo.core.model.BasketStatus
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.SavedBasket
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
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class SavedBasketManagerTest {

    private lateinit var context: Context
    private lateinit var savedBasketManager: SavedBasketManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSingleton()
        
        // Ensure clean state
        val prefs = context.getSharedPreferences("saved_baskets", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        
        savedBasketManager = SavedBasketManager.getInstance(context)
    }

    private fun resetSingleton() {
        try {
            val field = SavedBasketManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testSaveCurrentBasket() {
        // Mock BasketManager behavior
        val basketManager = mock<BasketManager>()
        val items = listOf(
            BasketItem(item = Item(name = "Pizza", price = 10.0), quantity = 2),
            BasketItem(item = Item(name = "Coke", price = 2.0), quantity = 3)
        )
        whenever(basketManager.getBasketItems()).thenReturn(items)
        
        val saved = savedBasketManager.saveCurrentBasket("Table 1", basketManager)
        
        assertNotNull(saved)
        assertEquals("Table 1", saved.name)
        assertEquals(2, saved.items.size)
        assertEquals(BasketStatus.ACTIVE, saved.status)
        
        // Verify it was persisted
        val loaded = savedBasketManager.getBasket(saved.id)
        assertNotNull(loaded)
        assertEquals("Pizza", loaded?.items?.get(0)?.item?.name)
    }

    @Test
    fun testUpdateExistingBasket() {
        val basketManager = mock<BasketManager>()
        val initialItems = listOf(BasketItem(item = Item(name = "A"), quantity = 1))
        whenever(basketManager.getBasketItems()).thenReturn(initialItems)
        
        // Create initial
        val saved = savedBasketManager.saveCurrentBasket("Original", basketManager)
        
        // Load for editing
        savedBasketManager.loadBasketForEditing(saved.id, mock())
        
        // Update
        val newItems = listOf(BasketItem(item = Item(name = "B"), quantity = 2))
        whenever(basketManager.getBasketItems()).thenReturn(newItems)
        
        val updated = savedBasketManager.saveCurrentBasket("Updated", basketManager)
        
        assertEquals(saved.id, updated.id)
        assertEquals("Updated", updated.name)
        assertEquals("B", updated.items[0].item.name)
        
        // Verify we only have 1 basket
        assertEquals(1, savedBasketManager.getSavedBaskets().size)
    }

    @Test
    fun testDeleteBasket() {
        val basketManager = mock<BasketManager>()
        whenever(basketManager.getBasketItems()).thenReturn(emptyList())
        
        val saved = savedBasketManager.saveCurrentBasket("Delete Me", basketManager)
        
        assertTrue(savedBasketManager.deleteBasket(saved.id))
        assertTrue(savedBasketManager.getSavedBaskets().isEmpty())
        assertNull(savedBasketManager.getBasket(saved.id))
    }

    @Test
    fun testMarkAsPaid() {
        val basketManager = mock<BasketManager>()
        whenever(basketManager.getBasketItems()).thenReturn(emptyList())
        
        val saved = savedBasketManager.saveCurrentBasket("To Pay", basketManager)
        val paymentId = "pay_123"
        
        val paid = savedBasketManager.markBasketAsPaid(saved.id, paymentId)
        
        assertNotNull(paid)
        assertEquals(BasketStatus.PAID, paid?.status)
        assertEquals(paymentId, paid?.paymentId)
        assertNotNull(paid?.paidAt)
        
        // Should be in archive, not in active
        assertTrue(savedBasketManager.getSavedBaskets().isEmpty())
        assertEquals(1, savedBasketManager.getArchivedBaskets().size)
        assertEquals(saved.id, savedBasketManager.getArchivedBaskets()[0].id)
    }

    @Test
    fun testPersistence() {
        val basketManager = mock<BasketManager>()
        val item = BasketItem(item = Item(name = "Persist"), quantity = 1)
        whenever(basketManager.getBasketItems()).thenReturn(listOf(item))
        
        savedBasketManager.saveCurrentBasket("Test", basketManager)
        
        // Re-create manager
        resetSingleton()
        val newManager = SavedBasketManager.getInstance(context)
        
        assertEquals(1, newManager.getSavedBaskets().size)
        assertEquals("Persist", newManager.getSavedBaskets()[0].items[0].item.name)
    }
}
