package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CheckoutBasketTest {

    private fun createItem(
        id: String,
        priceType: String = "FIAT",
        quantity: Int = 1,
        netPriceCents: Long = 1000,
        vatEnabled: Boolean = false,
        vatRate: Int = 0,
        priceSats: Long = 0
    ): CheckoutBasketItem {
        return CheckoutBasketItem(
            itemId = id,
            uuid = UUID.randomUUID().toString(),
            name = "Item $id",
            quantity = quantity,
            priceType = priceType,
            netPriceCents = netPriceCents,
            priceSats = priceSats,
            priceCurrency = "USD",
            vatEnabled = vatEnabled,
            vatRate = vatRate
        )
    }

    private fun createBasket(items: List<CheckoutBasketItem>): CheckoutBasket {
        return CheckoutBasket(
            items = items,
            currency = "USD",
            totalSatoshis = 1000 // Arbitrary total for test
        )
    }

    @Test
    fun `getFiatItems returns only FIAT items`() {
        val fiatItem = createItem("1", priceType = "FIAT")
        val satsItem = createItem("2", priceType = "SATS")
        val basket = createBasket(listOf(fiatItem, satsItem))

        val result = basket.getFiatItems()
        assertEquals(1, result.size)
        assertEquals("1", result[0].itemId)
    }

    @Test
    fun `getSatsItems returns only SATS items`() {
        val fiatItem = createItem("1", priceType = "FIAT")
        val satsItem = createItem("2", priceType = "SATS")
        val basket = createBasket(listOf(fiatItem, satsItem))

        val result = basket.getSatsItems()
        assertEquals(1, result.size)
        assertEquals("2", result[0].itemId)
    }

    @Test
    fun `hasMixedPriceTypes returns true if both types present`() {
        val fiatItem = createItem("1", priceType = "FIAT")
        val satsItem = createItem("2", priceType = "SATS")
        val basket = createBasket(listOf(fiatItem, satsItem))

        assertTrue(basket.hasMixedPriceTypes())
    }

    @Test
    fun `hasMixedPriceTypes returns false if only one type present`() {
        val fiatItem1 = createItem("1", priceType = "FIAT")
        val fiatItem2 = createItem("2", priceType = "FIAT")
        val basket = createBasket(listOf(fiatItem1, fiatItem2))

        assertFalse(basket.hasMixedPriceTypes())
    }

    @Test
    fun `hasVat returns true if any item has VAT enabled and rate positive`() {
        val noVatItem = createItem("1", vatEnabled = false)
        val vatItem = createItem("2", vatEnabled = true, vatRate = 20)
        val basket = createBasket(listOf(noVatItem, vatItem))

        assertTrue(basket.hasVat())
    }

    @Test
    fun `hasVat returns false if vat enabled but rate is 0`() {
        val item = createItem("1", vatEnabled = true, vatRate = 0)
        val basket = createBasket(listOf(item))

        assertFalse(basket.hasVat())
    }

    @Test
    fun `getTotalItemCount sums quantities`() {
        val item1 = createItem("1", quantity = 2)
        val item2 = createItem("2", quantity = 3)
        val basket = createBasket(listOf(item1, item2))

        assertEquals(5, basket.getTotalItemCount())
    }

    @Test
    fun `getFiatNetTotalCents sums net total of fiat items`() {
        val item1 = createItem("1", priceType = "FIAT", netPriceCents = 100, quantity = 2) // 200
        val item2 = createItem("2", priceType = "FIAT", netPriceCents = 200, quantity = 1) // 200
        val satsItem = createItem("3", priceType = "SATS", netPriceCents = 500) // Should be ignored
        val basket = createBasket(listOf(item1, item2, satsItem))

        assertEquals(400L, basket.getFiatNetTotalCents())
    }

    @Test
    fun `getFiatVatTotalCents sums vat of fiat items`() {
        // Item 1: 100 * 20% = 20 vat * 2 qty = 40 total vat
        val item1 = createItem("1", priceType = "FIAT", netPriceCents = 100, quantity = 2, vatEnabled = true, vatRate = 20)
        // Item 2: 200 * 10% = 20 vat * 1 qty = 20 total vat
        val item2 = createItem("2", priceType = "FIAT", netPriceCents = 200, quantity = 1, vatEnabled = true, vatRate = 10)
        val basket = createBasket(listOf(item1, item2))

        assertEquals(60L, basket.getFiatVatTotalCents())
    }

    @Test
    fun `getFiatGrossTotalCents sums gross total of fiat items`() {
        // Item 1: (100 + 20) * 2 = 240
        val item1 = createItem("1", priceType = "FIAT", netPriceCents = 100, quantity = 2, vatEnabled = true, vatRate = 20)
        // Item 2: (200 + 20) * 1 = 220
        val item2 = createItem("2", priceType = "FIAT", netPriceCents = 200, quantity = 1, vatEnabled = true, vatRate = 10)
        val basket = createBasket(listOf(item1, item2))

        assertEquals(460L, basket.getFiatGrossTotalCents())
    }

    @Test
    fun `getSatsDirectTotal sums sats items`() {
        val item1 = createItem("1", priceType = "SATS", priceSats = 100, quantity = 2) // 200
        val item2 = createItem("2", priceType = "SATS", priceSats = 50, quantity = 1)  // 50
        val fiatItem = createItem("3", priceType = "FIAT", netPriceCents = 1000)      // Ignored
        val basket = createBasket(listOf(item1, item2, fiatItem))

        assertEquals(250L, basket.getSatsDirectTotal())
    }

    @Test
    fun `getVatBreakdown groups by rate`() {
        // 20% VAT items
        val item1 = createItem("1", netPriceCents = 100, quantity = 1, vatEnabled = true, vatRate = 20) // 20 vat
        val item2 = createItem("2", netPriceCents = 200, quantity = 1, vatEnabled = true, vatRate = 20) // 40 vat

        // 10% VAT item
        val item3 = createItem("3", netPriceCents = 100, quantity = 1, vatEnabled = true, vatRate = 10) // 10 vat

        val basket = createBasket(listOf(item1, item2, item3))
        val breakdown = basket.getVatBreakdown()

        assertEquals(60L, breakdown[20])
        assertEquals(10L, breakdown[10])
        assertEquals(2, breakdown.size)
    }

    @Test
    fun `serialization works correctly`() {
        val item = createItem("1")
        val basket = createBasket(listOf(item))

        val json = basket.toJson()
        assertNotNull(json)
        assertTrue(json.contains("Item 1"))

        val deserialized = CheckoutBasket.fromJson(json)
        assertNotNull(deserialized)
        assertEquals(basket.id, deserialized?.id)
        assertEquals(1, deserialized?.items?.size)
    }

    @Test
    fun `fromJson returns null for empty string`() {
        assertNull(CheckoutBasket.fromJson(""))
        assertNull(CheckoutBasket.fromJson(null))
    }
    
    @Test
    fun `fromJson returns null for invalid json`() {
        assertNull(CheckoutBasket.fromJson("{invalid-json"))
    }
}
