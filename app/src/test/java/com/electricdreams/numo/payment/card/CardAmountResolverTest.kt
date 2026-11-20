package com.electricdreams.numo.payment.card

import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardAmountResolverTest {

    @Test
    fun `fiat amount entered in the card currency is charged exactly`() {
        val result = CardAmountResolver.resolve(
            enteredAmount = Amount(1_250L, Currency.USD),
            amountSats = 12_345L,
            currencyCode = "USD",
            btcPrice = 100_000.0,
        )

        assertEquals(CardAmountResolver.CardAmount(1_250L, "usd"), result)
    }

    @Test
    fun `sats amount is converted with the bitcoin price`() {
        // 25,000 sats at 80,000 per BTC = 20.00
        val result = CardAmountResolver.resolve(
            enteredAmount = Amount(25_000L, Currency.BTC),
            amountSats = 25_000L,
            currencyCode = "EUR",
            btcPrice = 80_000.0,
        )

        assertEquals(CardAmountResolver.CardAmount(2_000L, "eur"), result)
    }

    @Test
    fun `fiat amount in another currency falls back to price conversion`() {
        // Entered in EUR but the merchant charges cards in USD.
        val result = CardAmountResolver.resolve(
            enteredAmount = Amount(500L, Currency.EUR),
            amountSats = 10_000L,
            currencyCode = "USD",
            btcPrice = 60_000.0,
        )

        assertEquals(CardAmountResolver.CardAmount(600L, "usd"), result)
    }

    @Test
    fun `conversion rounds half up to the smallest currency unit`() {
        // 1,010 sats at 50,000 per BTC = 0.505 USD = 50.5 cents -> 51
        val result = CardAmountResolver.resolve(
            enteredAmount = null,
            amountSats = 1_010L,
            currencyCode = "USD",
            btcPrice = 50_000.0,
        )

        assertEquals(51L, result?.minorUnits)
    }

    @Test
    fun `zero decimal currencies use whole units`() {
        // 10,000 sats at 15,000,000 JPY per BTC = 1,500 JPY
        val result = CardAmountResolver.resolve(
            enteredAmount = null,
            amountSats = 10_000L,
            currencyCode = "JPY",
            btcPrice = 15_000_000.0,
        )

        assertEquals(CardAmountResolver.CardAmount(1_500L, "jpy"), result)
    }

    @Test
    fun `entered JPY amount is passed through without scaling`() {
        val result = CardAmountResolver.resolve(
            enteredAmount = Amount(1_500L, Currency.JPY),
            amountSats = 10_000L,
            currencyCode = "JPY",
            btcPrice = 0.0,
        )

        assertEquals(CardAmountResolver.CardAmount(1_500L, "jpy"), result)
    }

    @Test
    fun `missing price makes a sats amount unresolvable`() {
        val result = CardAmountResolver.resolve(
            enteredAmount = Amount(25_000L, Currency.BTC),
            amountSats = 25_000L,
            currencyCode = "USD",
            btcPrice = 0.0,
        )

        assertNull(result)
    }

    @Test
    fun `amounts that round to zero are rejected`() {
        val result = CardAmountResolver.resolve(
            enteredAmount = null,
            amountSats = 1L,
            currencyCode = "USD",
            btcPrice = 100.0,
        )

        assertNull(result)
    }

    @Test
    fun `unknown currency codes are rejected`() {
        val result = CardAmountResolver.resolve(
            enteredAmount = null,
            amountSats = 10_000L,
            currencyCode = "POINTS",
            btcPrice = 50_000.0,
        )

        assertNull(result)
    }
}
