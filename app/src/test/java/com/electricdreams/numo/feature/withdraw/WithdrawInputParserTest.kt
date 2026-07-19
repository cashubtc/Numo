package com.electricdreams.numo.feature.withdraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawInputParserTest {

    // Mirrors LightningAddressManager.isValidLightningAddress without Android deps
    private val addressValidator: (String) -> Boolean = { address ->
        val trimmed = address.trim()
        val parts = trimmed.split("@")
        parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[1].contains(".")
    }

    private fun parse(raw: String) = WithdrawInputParser.parse(raw, addressValidator)

    @Test
    fun `mainnet invoice is detected`() {
        val result = parse("lnbc1pj4xyzabc")
        assertTrue(result is WithdrawInputParser.Result.Bolt11)
        assertEquals("lnbc1pj4xyzabc", (result as WithdrawInputParser.Result.Bolt11).invoice)
    }

    @Test
    fun `uppercase invoice is detected`() {
        assertTrue(parse("LNBC1PJ4XYZABC") is WithdrawInputParser.Result.Bolt11)
    }

    @Test
    fun `testnet and regtest invoices are detected`() {
        assertTrue(parse("lntb1pj4xyz") is WithdrawInputParser.Result.Bolt11)
        assertTrue(parse("lnbcrt1pj4xyz") is WithdrawInputParser.Result.Bolt11)
    }

    @Test
    fun `lightning uri prefix is stripped`() {
        val result = parse("lightning:lnbc1pj4xyz")
        assertTrue(result is WithdrawInputParser.Result.Bolt11)
        assertEquals("lnbc1pj4xyz", (result as WithdrawInputParser.Result.Bolt11).invoice)
    }

    @Test
    fun `uri prefix is case insensitive`() {
        assertTrue(parse("LIGHTNING:lnbc1pj4xyz") is WithdrawInputParser.Result.Bolt11)
    }

    @Test
    fun `lightning address is detected`() {
        val result = parse("user@getalby.com")
        assertTrue(result is WithdrawInputParser.Result.LightningAddress)
        assertEquals("user@getalby.com", (result as WithdrawInputParser.Result.LightningAddress).address)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val result = parse("  user@getalby.com \n")
        assertTrue(result is WithdrawInputParser.Result.LightningAddress)
        assertEquals("user@getalby.com", (result as WithdrawInputParser.Result.LightningAddress).address)
    }

    @Test
    fun `garbage input is invalid`() {
        assertEquals(WithdrawInputParser.Result.Invalid, parse("not a destination"))
        assertEquals(WithdrawInputParser.Result.Invalid, parse("user@nodomain"))
        assertEquals(WithdrawInputParser.Result.Invalid, parse("@missing.local"))
    }

    @Test
    fun `blank input is invalid`() {
        assertEquals(WithdrawInputParser.Result.Invalid, parse(""))
        assertEquals(WithdrawInputParser.Result.Invalid, parse("   "))
        assertEquals(WithdrawInputParser.Result.Invalid, parse("lightning:"))
    }
}
