package com.electricdreams.shellshock.core.data.model

import com.cashujdk.nut00.Token
import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Represents a payment transaction in the history.
 * Stores comprehensive information about received payments.
 */
data class PaymentHistoryEntry(
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
) {

    /** Public, non-null view of the token unit. */
    fun getUnit(): String = rawUnit ?: "sat"

    /** Public, non-null view of the entry unit. */
    fun getEntryUnit(): String = rawEntryUnit ?: "sat"

    /**
     * Legacy-like secondary constructor for backward compatibility.
     */
    constructor(token: String, amount: Long, date: Date) : this(
        token = token,
        amount = amount,
        date = date,
        rawUnit = "sat",
        rawEntryUnit = "sat",
        enteredAmount = amount,
        bitcoinPrice = null,
        mintUrl = extractMintFromToken(token),
        paymentRequest = null,
    )

    companion object {
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
    }
}
