package com.electricdreams.shellshock.core.model

import java.text.NumberFormat
import java.util.Locale

/**
 * Represents a monetary amount with currency.
 * For BTC: [value] is satoshis.
 * For fiat currencies: [value] is minor units (e.g. cents).
 */
data class Amount(
    val value: Long,
    val currency: Currency,
) {
    enum class Currency(val symbol: String) {
        BTC("₿"),
        USD("$"),
        EUR("€"),
        GBP("£"),
        JPY("¥");

        companion object {
            @JvmStatic
            fun fromCode(code: String): Currency = when {
                code.equals("SAT", ignoreCase = true) ||
                    code.equals("SATS", ignoreCase = true) -> BTC
                else -> runCatching { valueOf(code.uppercase(Locale.US)) }
                    .getOrElse { USD }
            }
        }
    }

    override fun toString(): String {
        return when (currency) {
            Currency.BTC -> {
                val formatter = NumberFormat.getNumberInstance(Locale.US)
                "${currency.symbol}${formatter.format(value)}"
            }
            Currency.JPY -> {
                val major = value / 100.0
                String.format(Locale.US, "%s%.0f", currency.symbol, major)
            }
            else -> {
                val major = value / 100.0
                String.format(Locale.US, "%s%.2f", currency.symbol, major)
            }
        }
    }
}
