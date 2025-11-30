package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTest {

    @Test
    fun `net and gross price with VAT disabled`() {
        val item = Item(
            id = "1",
            name = "Test",
            price = 10.0,
            priceType = PriceType.FIAT,
            vatEnabled = false,
            vatRate = 0,
        )

        assertEquals(10.0, item.getNetPrice(), 0.0001)
        assertEquals(0.0, item.getVatAmount(), 0.0001)
        assertEquals(10.0, item.getGrossPrice(), 0.0001)
    }

    @Test
    fun `net and gross price with VAT enabled`() {
        val item = Item(
            id = "1",
            name = "Test",
            price = 100.0,
            priceType = PriceType.FIAT,
            vatEnabled = true,
            vatRate = 20,
        )

        assertEquals(100.0, item.getNetPrice(), 0.0001)
        assertEquals(20.0, item.getVatAmount(), 0.0001)
        assertEquals(120.0, item.getGrossPrice(), 0.0001)
    }

    @Test
    fun `sats pricing net and gross`() {
        val item = Item(
            id = "s1",
            name = "Sats item",
            priceType = PriceType.SATS,
            priceSats = 10_000L,
            vatEnabled = true,
            vatRate = 10,
        )

        assertEquals(0.0, item.getNetPrice(), 0.0001)
        assertEquals(10_000L, item.getNetSats())
        assertEquals(1_000L, item.getVatSats())
        assertEquals(11_000L, item.getGrossSats())
    }

    @Test
    fun `displayName combines name and variation`() {
        val item = Item(
            name = "Coffee",
            variationName = "Large",
        )

        assertEquals("Coffee - Large", item.displayName)

        val noVariation = Item(name = "Coffee", variationName = null)
        assertEquals("Coffee", noVariation.displayName)
    }

    @Test
    fun `calculateNetFromGross and calculateGrossFromNet are inverses`() {
        val gross = 119.0
        val vatRate = 19.0

        val net = Item.calculateNetFromGross(gross, vatRate)
        val backToGross = Item.calculateGrossFromNet(net, vatRate)

        assertEquals(gross, backToGross, 0.0001)
    }
}

