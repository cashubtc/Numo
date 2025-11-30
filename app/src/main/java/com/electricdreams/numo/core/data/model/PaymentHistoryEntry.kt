package com.electricdreams.numo.core.data.model

import com.cashujdk.nut00.Token
import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

/**
 * Represents a payment transaction in the history.
 * Stores comprehensive information about received payments.
 */
data class PaymentHistoryEntry(
    /** Unique identifier for this entry (for updates) */
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("token")
    val token: String,

    @SerializedName("amount")
    val amount: Long,

    @SerializedName("date")
    val date: Date,

    // Backing fields can be null (for safety with old/Java callers),
    // public getters always normalize to non-null with sensible defaults.
    @SerializedName("unit")
    private val rawUnit: String? = "sat", // Unit of the cashu token (e.g., "sat")

    @SerializedName("entryUnit")
    private val rawEntryUnit: String? = "sat", // Unit with which it was entered (e.g., "USD", "sat")

    @SerializedName("enteredAmount")
    val enteredAmount: Long, // Amount as it was entered (cents for fiat, sats for BTC)

    @SerializedName("bitcoinPrice")
    val bitcoinPrice: Double? = null, // Bitcoin price at time of payment (can be null)

    @SerializedName("mintUrl")
    val mintUrl: String? = null, // Mint from which it was received

    @SerializedName("paymentRequest")
    val paymentRequest: String? = null, // The payment request it was received with (optional)

    /** Payment status: "pending", "completed", or "cancelled" */
    @SerializedName("status")
    private val rawStatus: String? = "completed",

    /** Payment type: "cashu", "lightning", or null for pending/unknown */
    @SerializedName("paymentType")
    val paymentType: String? = null,

    /** Lightning invoice (BOLT11) - only for lightning payments */
    @SerializedName("lightningInvoice")
    val lightningInvoice: String? = null,

    /** Lightning mint quote ID - for resuming pending lightning payments */
    @SerializedName("lightningQuoteId")
    val lightningQuoteId: String? = null,

    /** Mint URL for lightning payment - for resuming */
    @SerializedName("lightningMintUrl")
    val lightningMintUrl: String? = null,

    /** Formatted amount string for display when resuming */
    @SerializedName("formattedAmount")
    val formattedAmount: String? = null,

    /** Nostr profile for resuming Cashu over Nostr payments */
    @SerializedName("nostrNprofile")
    val nostrNprofile: String? = null,

    /** Ephemeral nostr secret key (hex) for resuming NIP-17 listening */
    @SerializedName("nostrSecretHex")
    val nostrSecretHex: String? = null,

    /** Serialized checkout basket JSON (if payment originated from item checkout) - DEPRECATED, use basketId */
    @SerializedName("checkoutBasketJson")
    val checkoutBasketJson: String? = null,
    
    /** ID of the saved basket associated with this payment */
    @SerializedName("basketId")
    val basketId: String? = null,

    /** Tip amount in satoshis (separate from main amount for accounting) */
    @SerializedName("tipAmountSats")
    val tipAmountSats: Long = 0,

    /** Tip percentage selected (0 if custom amount, or preset like 5, 10, 15, 20) */
    @SerializedName("tipPercentage")
    val tipPercentage: Int = 0,

    /**
     * Optional metadata for swap-to-Lightning-mint flows.
     * Stores the unknown mint URL and the associated melt/mint quote IDs
     * so that the swap can be inspected or resumed from history.
     */
    @SerializedName("swapToLightningMintJson")
    val swapToLightningMintJson: String? = null,
) {

    /** Check if this payment includes a tip */
    fun hasTip(): Boolean = tipAmountSats > 0

    /** Get the base amount (excluding tip) in satoshis */
    fun getBaseAmountSats(): Long = amount - tipAmountSats

    /** Get tip formatted for display (e.g., "₿500" or "5%") */
    fun getTipDisplayString(): String {
        return if (tipAmountSats > 0) {
            com.electricdreams.numo.core.model.Amount(tipAmountSats, com.electricdreams.numo.core.model.Amount.Currency.BTC).toString()
        } else {
            ""
        }
    }

    /**
     * Get the checkout basket if this payment originated from item checkout.
     * Returns null for manual amount entry payments.
     */
    fun getCheckoutBasket(): com.electricdreams.numo.core.model.CheckoutBasket? {
        return com.electricdreams.numo.core.model.CheckoutBasket.fromJson(checkoutBasketJson)
    }

    /**
     * Check if this payment has associated checkout basket data.
     */
    fun hasCheckoutBasket(): Boolean = !checkoutBasketJson.isNullOrEmpty()
    
    /**
     * Check if this payment has an associated saved basket.
     */
    fun hasSavedBasket(): Boolean = !basketId.isNullOrEmpty()

    /** Public, non-null view of the token unit. */
    fun getUnit(): String = rawUnit ?: "sat"

    /** Public, non-null view of the entry unit. */
    fun getEntryUnit(): String = rawEntryUnit ?: "sat"

    /** Public, non-null view of the status. */
    fun getStatus(): String = rawStatus ?: "completed"

    /** Check if this payment is pending */
    fun isPending(): Boolean = getStatus() == STATUS_PENDING

    /** Check if this payment is completed */
    fun isCompleted(): Boolean = getStatus() == STATUS_COMPLETED

    /** Check if this payment was via Lightning */
    fun isLightning(): Boolean = paymentType == TYPE_LIGHTNING

    /** Check if this payment was via Cashu */
    fun isCashu(): Boolean = paymentType == TYPE_CASHU

    /** Get abbreviated lightning invoice for display (first 10 + ... + last 10 chars) */
    fun getAbbreviatedInvoice(): String? {
        val invoice = lightningInvoice ?: return null
        return if (invoice.length > 24) {
            "${invoice.take(12)}...${invoice.takeLast(12)}"
        } else {
            invoice
        }
    }

    /**
     * Legacy-like secondary constructor for backward compatibility.
     */
    constructor(token: String, amount: Long, date: Date) : this(
        id = UUID.randomUUID().toString(),
        token = token,
        amount = amount,
        date = date,
        rawUnit = "sat",
        rawEntryUnit = "sat",
        enteredAmount = amount,
        bitcoinPrice = null,
        mintUrl = extractMintFromToken(token),
        paymentRequest = null,
        rawStatus = STATUS_COMPLETED,
        paymentType = TYPE_CASHU,
    )

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"

        const val TYPE_CASHU = "cashu"
        const val TYPE_LIGHTNING = "lightning"

        /**
         * Simple DTO for recording swap-to-Lightning-mint metadata on a
         * PaymentHistoryEntry. Serialized as JSON into swapToLightningMintJson.
         */
        data class SwapToLightningMintFrame(
            val unknownMintUrl: String,
            val meltQuoteId: String,
            val lightningMintUrl: String,
            val lightningQuoteId: String,
        )

        /**
         * Extract mint URL from a cashu token.
         */
        @JvmStatic
        private fun extractMintFromToken(tokenString: String?): String? {
            return try {
                if (!tokenString.isNullOrEmpty()) {
                    val token = Token.decode(tokenString)
                    token.mint
                } else {
                    null
                }
            } catch (e: Exception) {
                // If we can't decode, return null
                null
            }
        }

        /**
         * Create a pending payment entry when payment request is initiated.
         */
        @JvmStatic
        fun createPending(
            amount: Long,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            paymentRequest: String?,
            formattedAmount: String?,
            checkoutBasketJson: String? = null,
            basketId: String? = null,
            tipAmountSats: Long = 0,
            tipPercentage: Int = 0,
        ): PaymentHistoryEntry {
            return PaymentHistoryEntry(
                id = UUID.randomUUID().toString(),
                token = "",
                amount = amount,
                date = Date(),
                rawUnit = "sat",
                rawEntryUnit = entryUnit,
                enteredAmount = enteredAmount,
                bitcoinPrice = bitcoinPrice,
                mintUrl = null,
                paymentRequest = paymentRequest,
                rawStatus = STATUS_PENDING,
                paymentType = null,
                formattedAmount = formattedAmount,
                checkoutBasketJson = checkoutBasketJson,
                basketId = basketId,
                tipAmountSats = tipAmountSats,
                tipPercentage = tipPercentage,
                swapToLightningMintJson = null,
            )
        }
    }
}
