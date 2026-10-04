package com.electricdreams.numo.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawDestinationTest {

    private val isValidAddress: (String) -> Boolean = {
        it.matches(Regex("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+"))
    }

    private fun parse(input: String) = WithdrawDestination.parse(input, isValidAddress)

    @Test
    fun `blank input is empty`() {
        assertEquals(WithdrawDestination.Empty, parse("  \n "))
    }

    @Test
    fun `lightning address is detected and trimmed`() {
        assertEquals(
            WithdrawDestination.LightningAddress("shop@wallet.com"),
            parse("  shop@wallet.com ")
        )
    }

    @Test
    fun `incomplete address is invalid`() {
        assertTrue(parse("shop@wallet") is WithdrawDestination.Invalid)
    }

    @Test
    fun `invoice with amount reports its sats`() {
        val result = parse(SPEC_INVOICE_2500U)
        assertEquals(WithdrawDestination.Invoice(SPEC_INVOICE_2500U, 250_000L), result)
    }

    @Test
    fun `amountless invoice has no amount`() {
        val result = parse(SPEC_INVOICE_NO_AMOUNT) as WithdrawDestination.Invoice
        assertNull(result.amountSats)
    }

    @Test
    fun `uppercase QR payload with lightning prefix becomes a lowercase invoice`() {
        val result = parse("LIGHTNING:" + SPEC_INVOICE_2500U.uppercase())
        assertEquals(WithdrawDestination.Invoice(SPEC_INVOICE_2500U, 250_000L), result)
    }

    @Test
    fun `invoice pasted with line breaks is joined`() {
        val wrapped = SPEC_INVOICE_2500U.chunked(40).joinToString("\n")
        assertEquals(WithdrawDestination.Invoice(SPEC_INVOICE_2500U, 250_000L), parse(wrapped))
    }

    @Test
    fun `bip21 link with a lightning parameter yields the invoice`() {
        val bip21 = "bitcoin:bc1qexample?amount=0.0025&lightning=$SPEC_INVOICE_2500U"
        assertEquals(WithdrawDestination.Invoice(SPEC_INVOICE_2500U, 250_000L), parse(bip21))
    }

    @Test
    fun `bip21 link without lightning is invalid`() {
        assertTrue(parse("bitcoin:bc1qexample?amount=0.001") is WithdrawDestination.Invalid)
    }

    @Test
    fun `lnurl is not mistaken for an invoice`() {
        assertTrue(parse("LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS") is WithdrawDestination.Invalid)
    }

    @Test
    fun `amount multipliers convert to sats`() {
        assertEquals(100_000L, WithdrawDestination.invoiceAmountSats("lnbc1m1pxyz"))
        assertEquals(2_500L, WithdrawDestination.invoiceAmountSats("lnbc25u1pxyz"))
        assertEquals(1L, WithdrawDestination.invoiceAmountSats("lnbc10n1pxyz"))
        assertEquals(0L, WithdrawDestination.invoiceAmountSats("lnbc10p1pxyz"))
        assertEquals(100_000_000L, WithdrawDestination.invoiceAmountSats("lnbc11pxyz"))
    }

    @Test
    fun `regtest and testnet prefixes are understood`() {
        assertEquals(100_000L, WithdrawDestination.invoiceAmountSats("lnbcrt1m1pxyz"))
        assertEquals(500L, WithdrawDestination.invoiceAmountSats("lntb5u1pxyz"))
    }

    @Test
    fun `unknown multiplier yields no amount`() {
        assertNull(WithdrawDestination.invoiceAmountSats("lnbc10x1pxyz"))
    }

    companion object {
        // BOLT11 specification examples.
        private const val SPEC_INVOICE_2500U =
            "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
        private const val SPEC_INVOICE_NO_AMOUNT =
            "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
    }
}
