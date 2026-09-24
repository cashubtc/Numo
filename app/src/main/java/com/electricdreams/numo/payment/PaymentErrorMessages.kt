package com.electricdreams.numo.payment

import android.content.Context
import androidx.annotation.StringRes
import com.electricdreams.numo.R

/**
 * Why a payment failed, in terms the merchant can act on.
 *
 * [noFundsMoved] is true only when the failure is known to happen before any
 * token or invoice could be settled, so the UI may reassure that retrying is safe.
 */
enum class PaymentErrorReason(@StringRes val messageRes: Int, val noFundsMoved: Boolean) {
    NFC_CONNECTION_LOST(R.string.payment_error_nfc_connection_lost, noFundsMoved = false),
    INVOICE_EXPIRED(R.string.payment_error_invoice_expired, noFundsMoved = true),
    NETWORK(R.string.payment_error_network, noFundsMoved = false),
    UNKNOWN_MINT(R.string.payment_error_unknown_mint, noFundsMoved = true),
    WRONG_UNIT(R.string.payment_error_wrong_unit, noFundsMoved = true),
    ALREADY_SPENT(R.string.payment_error_already_spent, noFundsMoved = false),
    TIMEOUT(R.string.payment_error_timeout, noFundsMoved = false),
    UNKNOWN(R.string.payment_error_unknown, noFundsMoved = false),
}

/**
 * Maps the raw, technical payment error text produced by the payment flows
 * (redemption exceptions, BTCPay/Nostr/Lightning handlers) to a localized,
 * human-readable reason. Callers should keep logging the raw message.
 */
object PaymentErrorMessages {

    /** Raw message for an NFC link that dropped while the customer's wallet was writing. */
    const val RAW_NFC_CONNECTION_LOST = "NFC connection lost mid-transaction"

    /** Raw message for the NFC safety timeout firing before a terminal outcome. */
    const val RAW_NFC_TIMEOUT = "NFC payment timed out before completing"

    private val SPENT_PATTERN = Regex("\\bspent\\b", RegexOption.IGNORE_CASE)

    private val NETWORK_MARKERS = listOf(
        "unreachable",
        "unable to resolve host",
        "unknownhost",
        "failed to connect",
        "connection refused",
        "connection reset",
        "timed out",
        "timeout",
        "network",
        "no address associated",
    )

    fun reasonFor(raw: String?): PaymentErrorReason {
        if (raw.isNullOrBlank()) return PaymentErrorReason.UNKNOWN
        if (raw == RAW_NFC_CONNECTION_LOST) return PaymentErrorReason.NFC_CONNECTION_LOST
        if (raw == RAW_NFC_TIMEOUT) return PaymentErrorReason.TIMEOUT

        val text = raw.lowercase()
        return when {
            "unknown mints are disabled" in text -> PaymentErrorReason.UNKNOWN_MINT
            "unsupported token unit" in text -> PaymentErrorReason.WRONG_UNIT
            SPENT_PATTERN.containsMatchIn(raw) -> PaymentErrorReason.ALREADY_SPENT
            "expired" in text -> PaymentErrorReason.INVOICE_EXPIRED
            NETWORK_MARKERS.any { it in text } -> PaymentErrorReason.NETWORK
            else -> PaymentErrorReason.UNKNOWN
        }
    }

    fun toUserMessage(context: Context, raw: String?): String =
        context.getString(reasonFor(raw).messageRes)
}
