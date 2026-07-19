package com.electricdreams.numo.feature.withdraw

import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WithdrawErrorMapperTest {

    @Test
    fun `primal description hash error maps to lnurl incompatible`() {
        // Exact message observed in the wild (CDK 0.17.x + primal.net)
        val raw = "code=50000, errorMessage=Failed to request invoice from Lightning address service: " +
            "Returned invoice description hash does not match LNURL metadata"
        val mapped = WithdrawErrorMapper.map(raw)
        assertEquals(R.string.withdraw_error_lnurl_incompatible, mapped.messageRes)
        assertNull(mapped.detail)
    }

    @Test
    fun `ffi wrapper is stripped for generic fallback`() {
        val mapped = WithdrawErrorMapper.map("code=12345, errorMessage=Weird unknown failure")
        assertEquals(R.string.withdraw_error_generic_detail, mapped.messageRes)
        assertEquals("Weird unknown failure", mapped.detail)
    }

    @Test
    fun `wrong invoice amount maps`() {
        val mapped = WithdrawErrorMapper.map(
            "Returned invoice amount 21000 msat does not match requested amount 20000 msat"
        )
        assertEquals(R.string.withdraw_error_lnurl_wrong_amount, mapped.messageRes)
    }

    @Test
    fun `insufficient funds maps`() {
        assertEquals(
            R.string.withdraw_error_insufficient_short,
            WithdrawErrorMapper.map("Insufficient funds").messageRes
        )
    }

    @Test
    fun `expired invoice maps`() {
        assertEquals(
            R.string.withdraw_error_expired,
            WithdrawErrorMapper.map("code=11000, errorMessage=Invoice Expired").messageRes
        )
    }

    @Test
    fun `already paid maps`() {
        assertEquals(
            R.string.withdraw_error_already_paid,
            WithdrawErrorMapper.map("Invoice already paid").messageRes
        )
    }

    @Test
    fun `network errors map`() {
        listOf(
            "Connection reset by peer",
            "Request timed out",
            "Network is unreachable",
            "failed to connect to mint.example.com"
        ).forEach { raw ->
            assertEquals("for: $raw", R.string.withdraw_error_network, WithdrawErrorMapper.map(raw).messageRes)
        }
    }

    @Test
    fun `unknown message falls through with detail`() {
        val mapped = WithdrawErrorMapper.map("gremlins in the channel")
        assertEquals(R.string.withdraw_error_generic_detail, mapped.messageRes)
        assertEquals("gremlins in the channel", mapped.detail)
    }

    @Test
    fun `null and blank map to plain generic`() {
        assertEquals(R.string.withdraw_error_generic, WithdrawErrorMapper.map(null).messageRes)
        assertEquals(R.string.withdraw_error_generic, WithdrawErrorMapper.map("   ").messageRes)
    }
}
