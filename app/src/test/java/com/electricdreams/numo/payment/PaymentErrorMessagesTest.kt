package com.electricdreams.numo.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentErrorMessagesTest {

    private fun reasonFor(raw: String?) = PaymentErrorMessages.reasonFor(raw)

    @Test
    fun `dropped NFC link maps to connection lost`() {
        assertEquals(
            PaymentErrorReason.NFC_CONNECTION_LOST,
            reasonFor(PaymentErrorMessages.RAW_NFC_CONNECTION_LOST),
        )
    }

    @Test
    fun `NFC safety timeout maps to timeout, not network`() {
        assertEquals(PaymentErrorReason.TIMEOUT, reasonFor(PaymentErrorMessages.RAW_NFC_TIMEOUT))
    }

    @Test
    fun `expired invoice maps to invoice expired`() {
        assertEquals(PaymentErrorReason.INVOICE_EXPIRED, reasonFor("Invoice expired"))
    }

    @Test
    fun `unreachable server and IO failures map to network`() {
        assertEquals(PaymentErrorReason.NETWORK, reasonFor("Server unreachable"))
        assertEquals(
            PaymentErrorReason.NETWORK,
            reasonFor(
                "NDEF Payment failed: Unable to resolve host \"mint.example.com\": " +
                    "No address associated with hostname",
            ),
        )
        assertEquals(PaymentErrorReason.NETWORK, reasonFor("Nostr payment error: timeout"))
    }

    @Test
    fun `disabled unknown mints maps to unknown mint`() {
        assertEquals(
            PaymentErrorReason.UNKNOWN_MINT,
            reasonFor("NDEF Payment failed: Payments from unknown mints are disabled in Settings   Mints."),
        )
    }

    @Test
    fun `unsupported unit maps to wrong unit`() {
        assertEquals(
            PaymentErrorReason.WRONG_UNIT,
            reasonFor("NDEF Payment failed: Unsupported token unit: usd, expected: sat"),
        )
    }

    @Test
    fun `spent proofs map to already spent`() {
        assertEquals(
            PaymentErrorReason.ALREADY_SPENT,
            reasonFor("NDEF Payment failed: Failed to redeem proofs: Token already spent"),
        )
        assertEquals(PaymentErrorReason.ALREADY_SPENT, reasonFor("Proofs are spent"))
    }

    @Test
    fun `unspent is not mistaken for spent`() {
        assertEquals(PaymentErrorReason.UNKNOWN, reasonFor("Failed to redeem token: 3 unspent proofs"))
    }

    @Test
    fun `anything else maps to unknown`() {
        assertEquals(PaymentErrorReason.UNKNOWN, reasonFor("BTCPay returned no payment methods"))
        assertEquals(PaymentErrorReason.UNKNOWN, reasonFor("Invoice invalid"))
        assertEquals(PaymentErrorReason.UNKNOWN, reasonFor(""))
        assertEquals(PaymentErrorReason.UNKNOWN, reasonFor(null))
    }

    @Test
    fun `reassurance only where no funds can have moved`() {
        val safe = PaymentErrorReason.values().filter { it.noFundsMoved }.toSet()
        assertEquals(
            setOf(
                PaymentErrorReason.INVOICE_EXPIRED,
                PaymentErrorReason.UNKNOWN_MINT,
                PaymentErrorReason.WRONG_UNIT,
            ),
            safe,
        )
        assertFalse(PaymentErrorReason.NFC_CONNECTION_LOST.noFundsMoved)
    }

    @Test
    fun `user message is the localized string for the reason`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = PaymentErrorMessages.toUserMessage(context, "Invoice expired")
        assertEquals(context.getString(R.string.payment_error_invoice_expired), message)
        assertTrue(message.isNotBlank())
        assertFalse(message.contains("Invoice expired"))
    }
}
