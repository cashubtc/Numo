package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTest {

    @Test
    fun `legacy VAT inclusive gross price survives migration and receipt capture`() {
        val item = Item(
            price = Item.calculateNetFromGross(3.99, 20.0),
            vatEnabled = true,
            vatRate = 20,
        )
        assertEquals(399L, item.getGrossAtomicAmount("usd").value)
        assertTrue(item.ensureExplicitPrice("usd"))
        assertEquals(333L, item.priceAtomic)
        assertEquals(399L, item.grossPriceAtomic)
        assertEquals(399L, item.getGrossAtomicAmount("eur").value)
        val receiptItem = CheckoutBasketItem.fromBasketItem(BasketItem(item, 2), "EUR")
        assertEquals(798L, receiptItem.getGrossLineAtomicAmount().value)
        assertEquals(UnitId.of("usd"), receiptItem.getGrossAtomicAmount().unit)
        org.junit.Assert.assertFalse(item.ensureExplicitPrice("eur"))
    }

    @Test
    fun `legacy sats VAT rounding survives migration`() {
        val item = Item(priceType = PriceType.SATS, priceSats = 5, vatEnabled = true, vatRate = 10)
        val original = item.getGrossSats()
        item.ensureExplicitPrice("USD")
        assertEquals(original, item.getGrossAtomicAmount("USD").value)
    }

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

    @Test
    fun `explicit custom price preserves unit issuer and atomic VAT rounding`() {
        val item = Item(
            name = "Reward",
            priceType = PriceType.FIAT,
            priceUnit = "POINTS",
            priceAtomic = 5L,
            priceIssuerScope = "https://rewards.example",
            vatEnabled = true,
            vatRate = 10,
        )

        assertTrue(item.ensureExplicitPrice("USD"))
        assertEquals("points", item.priceUnit)
        assertEquals(
            AssetId.mintScoped(UnitId.of("points"), "https://rewards.example"),
            item.resolvePriceAsset("USD"),
        )
        // 5 * 1.10 = 5.5, explicitly rounded half-up in the same atomic unit.
        assertEquals(6L, item.getGrossAtomicAmount("USD").value)
    }

    @Test
    fun `legacy prices migrate using their actual type rather than preferred charge unit`() {
        val satsItem = Item(priceType = PriceType.SATS, priceSats = 1_234L)
        val fiatItem = Item(priceType = PriceType.FIAT, price = 12.34)

        satsItem.ensureExplicitPrice("EUR")
        fiatItem.ensureExplicitPrice("EUR")

        assertEquals("sat", satsItem.priceUnit)
        assertEquals(1_234L, satsItem.priceAtomic)
        assertEquals("eur", fiatItem.priceUnit)
        assertEquals(1_234L, fiatItem.priceAtomic)
    }

    @Test
    fun `explicit item unit is not relabeled by display fallback`() {
        val item = Item(
            priceType = PriceType.SATS,
            priceSats = 500L,
            priceUnit = "sat",
            priceAtomic = 500L,
        )

        assertEquals(UnitId.SAT, item.getNetAtomicAmount("USD").unit)
    }
}
