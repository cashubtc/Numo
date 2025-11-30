package com.electricdreams.numo.core.util

import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BasketManagerTest {

    private lateinit var basketManager: BasketManager

    @Before
    fun setUp() {
        basketManager = BasketManager.getInstance()
        basketManager.clearBasket()
        basketManager.clearHistory()
    }

    private fun createFiatItem(id: String, price: Double): Item =
        Item(
            id = id,
            name = "Item $id",
            description = "Test item $id",
            category = "test",
            price = price,
            priceSats = 0L,
            priceType = PriceType.FIAT,
            vatEnabled = false,
            vatRate = 0,
            trackInventory = false,
            quantity = 0,
            alertEnabled = false,
            alertThreshold = 0,
            imagePath = null,
        )

    private fun createSatsItem(id: String, satsPrice: Long): Item =
        Item(
            id = id,
            name = "Item $id",
            description = "Test sats item $id",
            category = "test",
            price = 0.0,
            priceSats = satsPrice,
            priceType = PriceType.SATS,
            vatEnabled = false,
            vatRate = 0,
            trackInventory = false,
            quantity = 0,
            alertEnabled = false,
            alertThreshold = 0,
            imagePath = null,
        )

    @Test
    fun `adding single fiat item updates counts and totals`() {
        val item = createFiatItem("1", 10.0)

        basketManager.addItem(item, quantity = 2)

        assertEquals(2, basketManager.getTotalItemCount())
        assertEquals(20.0, basketManager.getTotalPrice(), 0.0001)
        assertEquals(0L, basketManager.getTotalSatsDirectPrice())
        assertFalse(basketManager.hasMixedPriceTypes())
    }

    @Test
    fun `adding sats item only affects sats totals`() {
        val satsItem = createSatsItem("s1", 5_000)

        basketManager.addItem(satsItem, quantity = 3)

        assertEquals(3, basketManager.getTotalItemCount())
        assertEquals(0.0, basketManager.getTotalPrice(), 0.0001)
        assertEquals(15_000L, basketManager.getTotalSatsDirectPrice())
        assertFalse(basketManager.hasMixedPriceTypes())
    }

    @Test
    fun `mixed fiat and sats items are detected correctly`() {
        val fiat = createFiatItem("1", 5.0)
        val sats = createSatsItem("2", 2_000)

        basketManager.addItem(fiat, quantity = 1)
        basketManager.addItem(sats, quantity = 1)

        assertTrue(basketManager.hasMixedPriceTypes())
        assertEquals(5.0, basketManager.getTotalPrice(), 0.0001)
        assertEquals(2_000L, basketManager.getTotalSatsDirectPrice())
    }

    @Test
    fun `getTotalSatoshis converts fiat using btc price and adds sats items`() {
        val fiat = createFiatItem("1", 100.0)
        val sats = createSatsItem("2", 10_000)

        basketManager.addItem(fiat, quantity = 1)      // 100 EUR
        basketManager.addItem(sats, quantity = 2)      // 20_000 sats

        // Suppose BTC price is 50_000 EUR
        val btcPrice = 50_000.0
        val totalSats = basketManager.getTotalSatoshis(btcPrice)

        // 100 / 50_000 BTC = 0.002 BTC = 200_000 sats, plus 20_000 = 220_000 sats
        assertEquals(220_000L, totalSats)
    }

    @Test
    fun `updateItemQuantity changes quantity and totals`() {
        val item = createFiatItem("1", 2.5)
        basketManager.addItem(item, quantity = 1)

        val updated = basketManager.updateItemQuantity(item.id!!, 4)

        assertTrue(updated)
        assertEquals(4, basketManager.getTotalItemCount())
        assertEquals(10.0, basketManager.getTotalPrice(), 0.0001)
    }

    @Test
    fun `updateItemQuantity to zero removes item`() {
        val item = createFiatItem("1", 3.0)
        basketManager.addItem(item, quantity = 2)

        val updated = basketManager.updateItemQuantity(item.id!!, 0)

        assertTrue(updated)
        assertEquals(0, basketManager.getTotalItemCount())
        assertEquals(0.0, basketManager.getTotalPrice(), 0.0001)
    }

    @Test
    fun `removeItem actually removes matching item`() {
        val item1 = createFiatItem("1", 1.0)
        val item2 = createFiatItem("2", 2.0)
        basketManager.addItem(item1, quantity = 1)
        basketManager.addItem(item2, quantity = 1)

        val removed = basketManager.removeItem(item1.id!!)

        assertTrue(removed)
        assertEquals(1, basketManager.getTotalItemCount())
        assertEquals(2.0, basketManager.getTotalPrice(), 0.0001)
    }

    @Test
    fun `clearBasket empties all items and resets totals`() {
        val item1 = createFiatItem("1", 1.0)
        val item2 = createSatsItem("2", 1_000)
        basketManager.addItem(item1, quantity = 1)
        basketManager.addItem(item2, quantity = 2)

        basketManager.clearBasket()

        assertEquals(0, basketManager.getTotalItemCount())
        assertEquals(0.0, basketManager.getTotalPrice(), 0.0001)
        assertEquals(0L, basketManager.getTotalSatsDirectPrice())
        assertFalse(basketManager.hasMixedPriceTypes())
    }
}
