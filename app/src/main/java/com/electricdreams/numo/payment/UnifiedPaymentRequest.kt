package com.electricdreams.numo.payment

import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Locale

/** Shared payload for the unified QR, sharing, and NFC. Methods are alternatives. */
data class UnifiedPaymentRequest(
    val amountSats: Long,
    val cashu: String? = null,
    val lightning: String? = null,
    val ark: String? = null,
    val lightningPending: Boolean = false,
    val arkoorPending: Boolean = false,
) {
    fun toUri(): String? {
        // Do not expose an incomplete unified request while either mint quote is loading.
        if (lightningPending || arkoorPending) return null
        if (cashu == null && lightning == null && ark == null) return null
        val params = mutableListOf<Pair<String, String>>()
        if (ark != null) {
            require(amountSats > 0) { "Ark payments require a positive amount" }
            // BIP321 amounts are decimal BTC, never floating point or localized text.
            params += "AMOUNT" to BigDecimal.valueOf(amountSats, 8).stripTrailingZeros().toPlainString()
            // Bark's BIP321 extension both parses and serializes the key "ark".
            params += "ARK" to ark.uppercase(Locale.ROOT)
        }
        lightning?.let { params += "LIGHTNING" to it.uppercase(Locale.ROOT) }
        cashu?.let {
            // Bech32 can be uppercase for denser QR codes; base64 creqA is case sensitive.
            params += "CREQ" to if (it.startsWith("creqb1", ignoreCase = true)) {
                it.uppercase(Locale.ROOT)
            } else it
        }
        return "BITCOIN:?" + params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
        }
    }
}
