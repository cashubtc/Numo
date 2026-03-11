package com.electricdreams.numo.core.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * Represents a monetary amount with currency.
 * For BTC: [value] is satoshis.
 * For fiat currencies: [value] is minor units (e.g. cents).
 * 
 * Formatting follows currency conventions:
 * - USD, GBP: period as decimal separator (e.g., $4.20, £4.20)
 * - EUR: comma as decimal separator (e.g., €4,20)
 * - JPY: no decimal places (e.g., ¥420)
 * - BTC: comma as thousand separator, no decimals (e.g., ₿1,000)
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
        JPY("¥"),
        DKK("kr."),
        SEK("kr"),
        NOK("kr"),
        KRW("₩");

        /** Returns true for currencies with no decimal places (e.g. JPY, KRW). */
        fun isZeroDecimal(): Boolean = this == JPY || this == KRW

        /**
         * Get the appropriate locale for formatting this currency.
         * This determines decimal separator conventions.
         */
        fun getLocale(): Locale = when (this) {
            USD -> Locale.US          // Period decimal: $4.20
            EUR -> Locale.GERMANY     // Comma decimal: €4,20
            GBP -> Locale.UK          // Period decimal: £4.20
            JPY -> Locale.JAPAN       // No decimals: ¥420
            BTC -> Locale.US          // Comma thousand separator: ₿1,000
            DKK -> Locale("da", "DK") // Comma decimal: DKK 100,00
            SEK -> Locale("sv", "SE") // Comma decimal: SEK 100,00
            NOK -> Locale("nb", "NO") // Comma decimal: NOK 100,00
            KRW -> Locale.KOREA       // No decimals: ₩101,816,000
        }

        companion object {
            @JvmStatic
            fun fromCode(code: String): Currency = when {
                code.equals("SAT", ignoreCase = true) ||
                    code.equals("SATS", ignoreCase = true) -> BTC
                else -> runCatching { valueOf(code.uppercase(Locale.US)) }
                    .getOrElse { USD }
            }

            /** Find currency by its symbol (e.g., "$" -> USD) */
            @JvmStatic
            fun fromSymbol(symbol: String): Currency? = entries.find { it.symbol == symbol }
        }
    }

    /**
     * Format the amount as a string with the currency symbol.
     * Uses currency-appropriate decimal separator:
     * - USD: $4.20
     * - EUR: €4,20
     * - GBP: £4.20
     * - JPY: ¥420
     * - BTC: ₿1,000
     */
    override fun toString(): String {
        return when (currency) {
            Currency.BTC -> {
                val formatter = NumberFormat.getNumberInstance(currency.getLocale())
                "${currency.symbol}${formatter.format(value)}"
            }
            Currency.JPY, Currency.KRW -> {
                // JPY/KRW have no decimal places (stored as cents internally, divide by 100)
                val major = value / 100.0
                val formatter = NumberFormat.getIntegerInstance(currency.getLocale())
                "${currency.symbol}${formatter.format(major.toLong())}"
            }
            else -> {
                // USD, EUR, GBP - 2 decimal places with currency-appropriate separator
                val major = value / 100.0
                val symbols = DecimalFormatSymbols(currency.getLocale())
                val formatter = DecimalFormat("#,##0.00", symbols)
                "${currency.symbol}${formatter.format(major)}"
            }
        }
    }

    /**
     * Format the amount without the currency symbol.
     * Useful for input fields and calculations.
     */
    fun toStringWithoutSymbol(): String {
        return when (currency) {
            Currency.BTC -> {
                val formatter = NumberFormat.getNumberInstance(currency.getLocale())
                formatter.format(value)
            }
            Currency.JPY, Currency.KRW -> {
                val major = value / 100.0
                val formatter = NumberFormat.getIntegerInstance(currency.getLocale())
                formatter.format(major.toLong())
            }
            else -> {
                val major = value / 100.0
                val symbols = DecimalFormatSymbols(currency.getLocale())
                val formatter = DecimalFormat("#,##0.00", symbols)
                formatter.format(major)
            }
        }
    }

    companion object {
        /**
         * Parse a formatted amount string back to an Amount.
         * Handles formats like "$0.25", "€1,50", "₿24", "¥100", etc.
         * Accepts both period and comma as decimal separators for input flexibility.
         * Returns null if parsing fails.
         * 
         * @param formatted The formatted string to parse (e.g. "$10.50")
         * @param defaultCurrency Optional default currency to use if the symbol is ambiguous (e.g. "kr" could be SEK or NOK).
         */
        @JvmStatic
        @JvmOverloads
        fun parse(formatted: String, defaultCurrency: Currency? = null): Amount? {
            if (formatted.isEmpty()) return null

            // Find matching currencies by matching the start of the string
            // We sort by symbol length descending to match longest symbols first (e.g. "kr." vs "kr")
            val matchingCurrencies = Currency.entries
                .filter { formatted.startsWith(it.symbol) }
                .sortedByDescending { it.symbol.length }
            
            if (matchingCurrencies.isEmpty()) return null
            
            // If we have multiple matches (e.g. SEK and NOK both use "kr"), try to use the default currency
            // Otherwise, pick the first one (which is deterministic but might be wrong if ambiguous)
            // Note: Since we sorted by length, "kr." (DKK) will come before "kr" (SEK/NOK), which is correct behavior.
            // The ambiguity is mainly between SEK and NOK.
            val currency = if (matchingCurrencies.size > 1 && defaultCurrency != null) {
                if (matchingCurrencies.contains(defaultCurrency)) {
                    defaultCurrency
                } else {
                    matchingCurrencies.first()
                }
            } else {
                matchingCurrencies.first()
            }
            
            // Extract the numeric part (remove symbol)
            var numericPart = formatted.drop(currency.symbol.length).trim()
            
            // Normalize the input: handle both comma and period as decimal separators
            // First, determine if comma is used as thousand separator or decimal separator
            numericPart = normalizeNumericInput(numericPart)
            
            return try {
                when (currency) {
                    Currency.BTC -> {
                        // For BTC, value is in satoshis (no decimal conversion needed)
                        val sats = numericPart.replace(",", "").toLong()
                        Amount(sats, currency)
                    }
                    Currency.JPY, Currency.KRW -> {
                        // JPY/KRW have no decimal places, but stored as cents internally
                        val amount = numericPart.replace(",", "").toDouble()
                        Amount((amount * 100).toLong(), currency)
                    }
                    else -> {
                        // For other fiat, convert decimal to minor units (cents)
                        val majorUnits = numericPart.toDouble()
                        val minorUnits = Math.round(majorUnits * 100)
                        Amount(minorUnits, currency)
                    }
                }
            } catch (e: NumberFormatException) {
                null
            }
        }

        /**
         * Create an Amount from a raw value (fiat in cents/minor units, BTC in sats).
         */
        @JvmStatic
        fun fromMinorUnits(minorUnits: Long, currency: Currency): Amount {
            return Amount(minorUnits, currency)
        }

        /**
         * Create an Amount from a major unit value (e.g., dollars not cents).
         * For BTC, this is treated as satoshis.
         */
        @JvmStatic
        fun fromMajorUnits(majorUnits: Double, currency: Currency): Amount {
            return when (currency) {
                Currency.BTC -> Amount(majorUnits.toLong(), currency)
                else -> Amount(Math.round(majorUnits * 100), currency)
            }
        }

        /**
         * Normalize numeric input to use period as decimal separator.
         * Handles inputs like "4,20" (European) or "4.20" (US) or "1,000.50" (US with thousands).
         */
        @JvmStatic
        fun normalizeNumericInput(input: String): String {
            var str = input.trim()
            
            // Count occurrences of period and comma
            val periodCount = str.count { it == '.' }
            val commaCount = str.count { it == ',' }
            
            return when {
                // No separators - just return as is
                periodCount == 0 && commaCount == 0 -> str
                
                // Only periods - could be decimal or thousand separators
                // If single period, treat as decimal; if multiple, treat as thousands
                periodCount > 0 && commaCount == 0 -> {
                    if (periodCount == 1) {
                        str // Single period = decimal separator
                    } else {
                        str.replace(".", "") // Multiple periods = thousand separators
                    }
                }
                
                // Only commas - could be decimal or thousand separators
                // If single comma with 1-2 digits after, treat as decimal; otherwise thousands
                commaCount > 0 && periodCount == 0 -> {
                    if (commaCount == 1) {
                        val parts = str.split(",")
                        if (parts.size == 2 && parts[1].length <= 2) {
                            // Single comma with 1-2 digits after = European decimal (e.g., "4,20")
                            str.replace(",", ".")
                        } else {
                            // Otherwise treat as thousand separator (e.g., "1,000")
                            str.replace(",", "")
                        }
                    } else {
                        // Multiple commas = thousand separators
                        str.replace(",", "")
                    }
                }
                
                // Both period and comma present - determine which is decimal
                else -> {
                    val lastPeriod = str.lastIndexOf('.')
                    val lastComma = str.lastIndexOf(',')
                    
                    if (lastPeriod > lastComma) {
                        // Period is last, so comma is thousand separator (US format: 1,000.50)
                        str.replace(",", "")
                    } else {
                        // Comma is last, so period is thousand separator (EU format: 1.000,50)
                        str.replace(".", "").replace(",", ".")
                    }
                }
            }
        }
    }
}
