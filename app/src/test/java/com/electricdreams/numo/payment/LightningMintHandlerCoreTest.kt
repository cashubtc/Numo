package com.electricdreams.numo.payment

import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrow tests for the shared quote connection's URL builder and CDK amounts.
 *
 * We deliberately avoid exercising WebSocket / polling orchestration and instead
 * focus on small, pure pieces of behavior that can be tested in isolation.
 */
class LightningMintHandlerCoreTest {

    @Test
    fun `buildMintWsUrl builds expected ws url for https base`() {
        val mintUrl = MintUrl("https://mint.minibits.cash/Bitcoin")
        val wsUrl = MintQuoteWebSocket.buildWsUrl(mintUrl.url)

        assertEquals("wss://mint.minibits.cash/Bitcoin/v1/ws", wsUrl)
    }

    @Test
    fun `buildMintWsUrl builds expected ws url for http base`() {
        val mintUrl = MintUrl("http://example.com")
        val wsUrl = MintQuoteWebSocket.buildWsUrl(mintUrl.url)

        assertEquals("ws://example.com/v1/ws", wsUrl)
    }

    @Test
    fun `buildMintWsUrl leaves ws scheme untouched`() {
        val mintUrl = MintUrl("wss://custom.mint.local/path")
        val wsUrl = MintQuoteWebSocket.buildWsUrl(mintUrl.url)

        assertEquals("wss://custom.mint.local/path/v1/ws", wsUrl)
    }

    /**
     * Sanity-check that the CDK [Amount] type we pass into the wallet
     * represents satoshis as expected.
     */
    @Test
    fun `cdk amount represents satoshis correctly`() {
        val sats = 50_000L
        val amount = CdkAmount(sats.toULong())

        assertEquals(sats.toULong(), amount.value)
        // Basic check that the CurrencyUnit enum exposes a SAT unit.
        assertTrue(CurrencyUnit.Sat.toString().contains("Sat", ignoreCase = true))
    }
}
