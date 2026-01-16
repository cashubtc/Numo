package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SavedBasketTest {

    private fun createBasketItem(name: String, quantity: Int, price: Double = 10.0, isSats: Boolean = false): BasketItem {
        val item = Item(
            name = name,
            price = if (!isSats) price else 0.0,
            priceSats = if (isSats) price.toLong() else 0L,
            priceType = if (isSats) PriceType.SATS else PriceType.FIAT
        )
        return BasketItem(item, quantity)
    }

    private fun createBasket(items: List<BasketItem>, name: String? = null, status: BasketStatus = BasketStatus.ACTIVE): SavedBasket {
        return SavedBasket(
            items = items,
            name = name,
            status = status
        )
    }

    @Test
    fun `getDisplayName returns custom name if present`() {
        val basket = createBasket(emptyList(), name = "Table 5")
        assertEquals("Table 5", basket.getDisplayName(0))
    }

    @Test
    fun `getDisplayName returns default if name is null`() {
        val basket = createBasket(emptyList(), name = null)
        assertEquals("Basket #1", basket.getDisplayName(0))
    }

    @Test
    fun `getDisplayName returns default if name is blank`() {
        val basket = createBasket(emptyList(), name = "   ")
        assertEquals("Basket #3", basket.getDisplayName(2))
    }

    @Test
    fun `getTotalItemCount sums quantities`() {
        val items = listOf(
            createBasketItem("A", 2),
            createBasketItem("B", 3)
        )
        val basket = createBasket(items)
        assertEquals(5, basket.getTotalItemCount())
    }

    @Test
    fun `getTotalFiatPrice sums only fiat items`() {
        val items = listOf(
            createBasketItem("A", 2, price = 10.0), // 20.0
            createBasketItem("B", 1, price = 5.0),  // 5.0
            createBasketItem("C", 10, price = 100.0, isSats = true) // Ignored
        )
        val basket = createBasket(items)
        assertEquals(25.0, basket.getTotalFiatPrice(), 0.001)
    }

    @Test
    fun `getTotalSatsPrice sums only sats items`() {
        val items = listOf(
            createBasketItem("A", 2, price = 10.0), // Ignored
            createBasketItem("C", 2, price = 100.0, isSats = true) // 200
        )
        val basket = createBasket(items)
        assertEquals(200L, basket.getTotalSatsPrice())
    }

    @Test
    fun `hasMixedPriceTypes`() {
        val fiat = createBasketItem("A", 1, isSats = false)
        val sats = createBasketItem("B", 1, isSats = true)

        assertTrue(createBasket(listOf(fiat, sats)).hasMixedPriceTypes())
        assertFalse(createBasket(listOf(fiat)).hasMixedPriceTypes())
        assertFalse(createBasket(listOf(sats)).hasMixedPriceTypes())
    }

    @Test
    fun `isActive and isPaid`() {
        val active = createBasket(emptyList(), status = BasketStatus.ACTIVE)
        assertTrue(active.isActive())
        assertFalse(active.isPaid())

        val paid = createBasket(emptyList(), status = BasketStatus.PAID)
        assertFalse(paid.isActive())
        assertTrue(paid.isPaid())
    }

    @Test
    fun `getItemsSummary formats correctly`() {
        val items = listOf(
            createBasketItem("Pizza", 2),
            createBasketItem("Coke", 3)
        )
        val basket = createBasket(items)
        
        // Output order depends on sorting by quantity descending:
        // Coke (3) then Pizza (2)
        assertEquals("3 × Coke, 2 × Pizza", basket.getItemsSummary(maxItems = 5))
    }

    @Test
    fun `getItemsSummary truncates with more count`() {
        val items = listOf(
            createBasketItem("A", 5),
            createBasketItem("B", 4),
            createBasketItem("C", 3)
        )
        val basket = createBasket(items)
        
        // Should show A and B, + 1 more
        assertEquals("5 × A, 4 × B + 1 more", basket.getItemsSummary(maxItems = 2))
    }
    
    @Test
    fun `getItemsSummary returns empty string for empty basket`() {
        val basket = createBasket(emptyList())
        assertEquals("", basket.getItemsSummary())
    }
}
