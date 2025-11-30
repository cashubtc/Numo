package com.electricdreams.numo.core.model

import org.junit.Assert.*
import org.junit.Test

class BasketItemTest {

    private fun fiatItem(price: Double): Item =
        Item(
            id = "1",
            name = "Test",
            price = price,
            priceType = PriceType.FIAT,
        )

    private fun satsItem(sats: Long): Item =
        Item(
            id = "2",
            name = "Sats",
            priceType = PriceType.SATS,
            priceSats = sats,
        )

    @Test
    fun `increase and decreaseQuantity behave correctly`() {
        val basketItem = BasketItem(fiatItem(5.0), quantity = 1)

        basketItem.increaseQuantity()
        assertEquals(2, basketItem.quantity)

        val stillPositive = basketItem.decreaseQuantity()
        assertTrue(stillPositive)
        assertEquals(1, basketItem.quantity)

        val nowZero = basketItem.decreaseQuantity()
        assertFalse(nowZero)
        assertEquals(0, basketItem.quantity)
    }

    @Test
    fun `getTotalPrice multiplies gross price by quantity`() {
        val item = fiatItem(10.0)
        val basketItem = BasketItem(item, quantity = 3)

        assertEquals(30.0, basketItem.getTotalPrice(), 0.0001)
    }

    @Test
    fun `getTotalSats and isSatsPrice work`() {
        val item = satsItem(1_000L)
        val basketItem = BasketItem(item, quantity = 4)

        assertTrue(basketItem.isSatsPrice())
        assertEquals(4_000L, basketItem.getTotalSats())
    }
}

