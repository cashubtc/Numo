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
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class SavedBasketManagerTest {

    @Test
    fun `active and archived legacy baskets retain ecash unit and original gross price`() {
        CurrencyManager.getInstance(context).setPreferredCurrency("EUR")
        MintManager.getInstance(context).setPreferredUnit("usd")
        val item = JSONObject().put("id", "coffee").put("name", "Coffee")
            .put("price", Item.calculateNetFromGross(3.99, 20.0))
            .put("priceType", "FIAT").put("vatEnabled", true).put("vatRate", 20)
        fun basket(id: String, status: String) = JSONObject()
            .put("id", id).put("status", status).put("createdAt", 1L).put("updatedAt", 1L)
            .put("items", JSONArray().put(JSONObject().put("quantity", 2).put("item", item)))
        context.getSharedPreferences("saved_baskets", Context.MODE_PRIVATE).edit()
            .putString("baskets", JSONArray().put(basket("active", "ACTIVE")).toString())
            .putString("archived_baskets", JSONArray().put(basket("paid", "PAID")).toString())
            .commit()
        resetSingleton()
        val migrated = SavedBasketManager.getInstance(context)
        for (saved in migrated.getSavedBaskets() + migrated.getArchivedBaskets()) {
            assertEquals("usd", saved.items.single().item.priceUnit)
            assertEquals(798L, saved.items.single().getGrossAtomicAmount("EUR").value)
        }
        MintManager.getInstance(context).setPreferredUnit("eur")
        resetSingleton()
        val reloaded = SavedBasketManager.getInstance(context)
        for (saved in reloaded.getSavedBaskets() + reloaded.getArchivedBaskets()) {
            assertEquals("usd", saved.items.single().item.priceUnit)
            assertEquals(399L, saved.items.single().item.grossPriceAtomic)
        }
    }

    private lateinit var context: Context
    private lateinit var savedBasketManager: SavedBasketManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSingleton()
        
        // Ensure clean state
        val prefs = context.getSharedPreferences("saved_baskets", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        CurrencyManager.getInstance(context).setPreferredCurrency(CurrencyManager.CURRENCY_USD)
        MintManager.getInstance(context).setPreferredUnit("sat")
        
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
    fun `saved basket takes an immutable unit aware item snapshot`() {
        val basketManager = mock<BasketManager>()
        val source = Item(name = "Coffee", price = 2.5)
        whenever(basketManager.getBasketItems()).thenReturn(listOf(BasketItem(source, 1)))

        val saved = savedBasketManager.saveCurrentBasket("Table", basketManager)
        source.name = "Changed"
        source.price = 99.0

        val snapshot = saved.items.single().item
        assertEquals("Coffee", snapshot.name)
        assertEquals("usd", snapshot.priceUnit)
        assertEquals(250L, snapshot.priceAtomic)
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

    @Test
    fun `legacy saved basket price is pinned to currency during load`() {
        val legacyItem = JSONObject()
            .put("id", "item-1")
            .put("name", "Coffee")
            .put("price", 1.25)
            .put("priceType", "FIAT")
        val basket = JSONObject()
            .put("id", "basket-1")
            .put("createdAt", 1L)
            .put("updatedAt", 1L)
            .put("status", "ACTIVE")
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("quantity", 1)
                        .put("item", legacyItem),
                ),
            )
        context.getSharedPreferences("saved_baskets", Context.MODE_PRIVATE)
            .edit()
            .putString("baskets", JSONArray().put(basket).toString())
            .commit()

        resetSingleton()
        val loaded = SavedBasketManager.getInstance(context).getSavedBaskets().single()

        assertEquals("usd", loaded.items.single().item.priceUnit)
        assertEquals(125L, loaded.items.single().item.priceAtomic)
        val persisted = context.getSharedPreferences("saved_baskets", Context.MODE_PRIVATE)
            .getString("baskets", "")
            .orEmpty()
        assertTrue(persisted.contains("\"priceUnit\":\"usd\""))
        assertTrue(persisted.contains("\"priceAtomic\":125"))
    }

    @Test
    fun `one corrupt saved basket does not hide valid baskets`() {
        fun basket(id: String, quantity: Int): JSONObject = JSONObject()
            .put("id", id)
            .put("createdAt", 1L)
            .put("updatedAt", 1L)
            .put("status", "ACTIVE")
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("quantity", quantity)
                        .put(
                            "item",
                            JSONObject()
                                .put("id", "item-$id")
                                .put("name", id)
                                .put("price", 1.0)
                                .put("priceType", "FIAT"),
                        ),
                ),
            )

        context.getSharedPreferences("saved_baskets", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "baskets",
                JSONArray()
                    .put(basket("invalid", 0))
                    .put(basket("valid", 1))
                    .toString(),
            )
            .commit()

        resetSingleton()
        val loaded = SavedBasketManager.getInstance(context).getSavedBaskets()

        assertEquals(1, loaded.size)
        assertEquals("valid", loaded.single().id)
    }
}
