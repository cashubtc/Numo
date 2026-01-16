package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CheckoutBasketItemTest {

    private fun createItem(
        priceType: String = "FIAT",
        quantity: Int = 1,
        netPriceCents: Long = 1000, // $10.00
        vatEnabled: Boolean = false,
        vatRate: Int = 0,
        priceSats: Long = 0,
        variationName: String? = null
    ): CheckoutBasketItem {
        return CheckoutBasketItem(
            itemId = "item-1",
            uuid = UUID.randomUUID().toString(),
            name = "Test Item",
            variationName = variationName,
            sku = "SKU-123",
            category = "General",
            quantity = quantity,
            priceType = priceType,
            netPriceCents = netPriceCents,
            priceSats = priceSats,
            priceCurrency = "USD",
            vatEnabled = vatEnabled,
            vatRate = vatRate
        )
    }

    @Test
    fun `displayName includes variation if present`() {
        val itemWithVar = createItem(variationName = "Large")
        assertEquals("Test Item - Large", itemWithVar.displayName)

        val itemWithoutVar = createItem(variationName = null)
        assertEquals("Test Item", itemWithoutVar.displayName)
    }

    @Test
    fun `getNetTotalCents calculates correctly`() {
        val item = createItem(quantity = 3, netPriceCents = 1000)
        assertEquals(3000L, item.getNetTotalCents())
    }

    @Test
    fun `getNetTotalSats calculates correctly`() {
        val item = createItem(
            priceType = "SATS",
            quantity = 2,
            priceSats = 500
        )
        assertEquals(1000L, item.getNetTotalSats())
    }

    @Test
    fun `getVatPerUnitCents returns 0 when VAT disabled`() {
        val item = createItem(vatEnabled = false, vatRate = 20)
        assertEquals(0L, item.getVatPerUnitCents())
    }

    @Test
    fun `getVatPerUnitCents returns 0 when price type is SATS`() {
        val item = createItem(
            priceType = "SATS",
            vatEnabled = true,
            vatRate = 20
        )
        assertEquals(0L, item.getVatPerUnitCents())
    }

    @Test
    fun `getVatPerUnitCents calculates correctly for FIAT`() {
        // 1000 cents * 20% = 200 cents
        val item = createItem(
            priceType = "FIAT",
            netPriceCents = 1000,
            vatEnabled = true,
            vatRate = 20
        )
        assertEquals(200L, item.getVatPerUnitCents())
    }

    @Test
    fun `getTotalVatCents calculates correctly`() {
        // (1000 * 20%) * 3 items = 600 cents
        val item = createItem(
            quantity = 3,
            netPriceCents = 1000,
            vatEnabled = true,
            vatRate = 20
        )
        assertEquals(600L, item.getTotalVatCents())
    }

    @Test
    fun `getGrossPricePerUnitCents includes VAT`() {
        // 1000 + 200 = 1200
        val item = createItem(
            netPriceCents = 1000,
            vatEnabled = true,
            vatRate = 20
        )
        assertEquals(1200L, item.getGrossPricePerUnitCents())
    }

    @Test
    fun `getGrossTotalCents includes VAT and quantity`() {
        // (1000 + 200) * 3 = 3600
        val item = createItem(
            quantity = 3,
            netPriceCents = 1000,
            vatEnabled = true,
            vatRate = 20
        )
        assertEquals(3600L, item.getGrossTotalCents())
    }

    @Test
    fun `isSatsPrice identifies correctly`() {
        val satsItem = createItem(priceType = "SATS")
        assertTrue(satsItem.isSatsPrice())
        assertFalse(satsItem.isFiatPrice())

        val fiatItem = createItem(priceType = "FIAT")
        assertFalse(fiatItem.isSatsPrice())
        assertTrue(fiatItem.isFiatPrice())
    }
}
