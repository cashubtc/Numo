package com.electricdreams.numo.core.wallet

/**
 * Domain types for the wallet abstraction layer.
 * These types are independent of any specific wallet implementation (CDK, BTCPay, etc.)
 */

/**
 * Represents an amount in satoshis.
 */
@JvmInline
value class Satoshis(val value: Long) {
    init {
        require(value >= 0) { "Satoshis cannot be negative" }
    }

    operator fun plus(other: Satoshis): Satoshis = Satoshis(value + other.value)
    operator fun minus(other: Satoshis): Satoshis = Satoshis(value - other.value)
    operator fun compareTo(other: Satoshis): Int = value.compareTo(other.value)

    companion object {
        val ZERO = Satoshis(0)

        fun fromULong(value: ULong): Satoshis = Satoshis(value.toLong())
    }
}

/**
 * Status of a mint or melt quote.
 */
enum class QuoteStatus {
    /** Quote is created but not yet paid */
    UNPAID,
    /** Payment is pending/in-progress */
    PENDING,
    /** Quote is paid */
    PAID,
    /** Proofs have been issued (for mint quotes) */
    ISSUED,
    /** Quote has expired */
    EXPIRED,
    /** Unknown status */
    UNKNOWN
}

/**
 * Result of creating a mint quote (Lightning receive).
 */
data class MintQuoteResult(
    /** Unique identifier for this quote */
    val quoteId: String,
    /** BOLT11 Lightning invoice to be paid */
    val bolt11Invoice: String,
    /** Amount requested in satoshis */
    val amount: Satoshis,
    /** Current status of the quote */
    val status: QuoteStatus,
    /** Unix timestamp when the quote expires (optional) */
    val expiryTimestamp: Long? = null
)

/**
 * Result of checking a mint quote status.
 */
data class MintQuoteStatusResult(
    /** Unique identifier for this quote */
    val quoteId: String,
    /** Current status of the quote */
    val status: QuoteStatus,
    /** Unix timestamp when the quote expires (optional) */
    val expiryTimestamp: Long? = null
)

/**
 * Result of minting proofs (after Lightning invoice is paid).
 */
data class MintResult(
    /** Number of proofs minted */
    val proofsCount: Int,
    /** Total amount minted in satoshis */
    val amount: Satoshis
)

/**
 * Result of creating a melt quote (Lightning spend).
 */
data class MeltQuoteResult(
    /** Unique identifier for this quote */
    val quoteId: String,
    /** Amount to be melted (paid) in satoshis */
    val amount: Satoshis,
    /** Fee reserve required for this payment */
    val feeReserve: Satoshis,
    /** Current status of the quote */
    val status: QuoteStatus,
    /** Unix timestamp when the quote expires (optional) */
    val expiryTimestamp: Long? = null
)

/**
 * Result of executing a melt operation.
 */
data class MeltResult(
    /** Whether the melt was successful */
    val success: Boolean,
    /** Current status after melt */
    val status: QuoteStatus,
    /** Actual fee paid (may be less than reserved) */
    val feePaid: Satoshis,
    /** Payment preimage (proof of payment) if available */
    val preimage: String? = null,
    /** Change proofs count (if any change was returned) */
    val changeProofsCount: Int = 0
)

/**
 * Information about a received Cashu token.
 */
data class TokenInfo(
    /** Mint URL the token is from */
    val mintUrl: String,
    /** Total value of the token in satoshis */
    val amount: Satoshis,
    /** Number of proofs in the token */
    val proofsCount: Int,
    /** Currency unit (should be "sat" for satoshis) */
    val unit: String
)

/**
 * Result of receiving (redeeming) a Cashu token.
 */
data class ReceiveResult(
    /** Amount received in satoshis */
    val amount: Satoshis,
    /** Number of proofs received */
    val proofsCount: Int
)

/**
 * Version information for a mint.
 */
data class MintVersionInfo(
    val name: String?,
    val version: String?
)

/**
 * Contact information for a mint.
 */
data class MintContactInfo(
    val method: String,
    val info: String
)

/**
 * Information about a mint.
 */
data class MintInfoResult(
    /** Human-readable name of the mint */
    val name: String?,
    /** Short description */
    val description: String?,
    /** Detailed description */
    val descriptionLong: String?,
    /** Mint's public key */
    val pubkey: String?,
    /** Version information */
    val version: MintVersionInfo?,
    /** Message of the day */
    val motd: String?,
    /** URL to mint's icon/logo */
    val iconUrl: String?,
    /** Contact information */
    val contacts: List<MintContactInfo>
)

/**
 * Keyset information from a mint.
 */
data class KeysetInfo(
    /** Keyset identifier */
    val id: String,
    /** Whether this keyset is active */
    val active: Boolean,
    /** Currency unit for this keyset */
    val unit: String
)
