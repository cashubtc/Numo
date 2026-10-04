package com.electricdreams.numo.feature.settings

import org.cashudevkit.FfiException

/**
 * How a failed melt should be presented. Only an explicit refusal from the mint means
 * the money did not move; anything else (a dropped connection, an internal error) may
 * still settle, so it must never invite the merchant to pay a second time.
 */
enum class MeltOutcome {
    /** The mint answered and refused: nothing left the balance, retrying is safe. */
    REFUSED,

    /** The mint reports the payment as still in flight. */
    PENDING,

    /** We could not learn the result; the payment may or may not have been made. */
    UNCONFIRMED;

    companion object {
        // NUT error codes that are definite refusals for a melt.
        private val REFUSAL_CODES = setOf(
            11002, // transaction unbalanced
            11005, // unit not supported
            11006, // amount outside the mint's limits
            12001, // keyset unknown
            12002, // keyset inactive
            20004, // Lightning payment failed
            20006, // invoice already paid
            20007, // quote expired
        )
        private const val QUOTE_PENDING = 20005

        fun forCode(code: Int): MeltOutcome = when (code) {
            in REFUSAL_CODES -> REFUSED
            QUOTE_PENDING -> PENDING
            else -> UNCONFIRMED
        }

        fun forError(error: Throwable): MeltOutcome = when (error) {
            is FfiException.Cdk -> forCode(error.code.toInt())
            else -> UNCONFIRMED
        }
    }
}
