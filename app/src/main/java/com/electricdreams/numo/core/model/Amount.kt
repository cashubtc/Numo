package com.electricdreams.numo.core.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import java.util.Currency as JavaCurrency

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
    class Currency private constructor(
        val name: String,
        val symbol: String,
        val isBtc: Boolean = false
    ) {
        /** Returns true for currencies with no decimal places (e.g. JPY, KRW). */
        fun isZeroDecimal(): Boolean {
            if (isBtc) return true
            
            // ISO 4217 specifies decimals for some highly inflated currencies that never use them in practice
            val practicalZeroDecimal = setOf("COP", "VND", "IDR", "CLP", "ARS", "VES", "LBP", "UGX", "ZWL", "GNF", "PYG")
            if (name in practicalZeroDecimal) return true
            
            return runCatching {
                JavaCurrency.getInstance(name).defaultFractionDigits == 0
            }.getOrDefault(false)
        }

        /**
         * Get the appropriate locale for formatting this currency.
         * This determines decimal separator conventions.
         */
        fun getLocale(): Locale {
            if (isBtc) return Locale.US // BTC formatting remains consistent (comma thousands, period decimals)
            return Locale.getDefault() // Format fiat according to the user's system preferences
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Currency) return false
            return name == other.name
        }

        override fun hashCode(): Int {
            return name.hashCode()
        }

        companion object {
            @JvmField
            val BTC = Currency("BTC", "₿", true)
            @JvmField
            val USD = Currency("USD", "$")
            @JvmField
            val EUR = Currency("EUR", "€")
            @JvmField
            val GBP = Currency("GBP", "£")
            @JvmField
            val JPY = Currency("JPY", "¥")
            @JvmField
            val DKK = Currency("DKK", "kr.")
            @JvmField
            val SEK = Currency("SEK", "kr")
            @JvmField
            val NOK = Currency("NOK", "kr")
            @JvmField
            val KRW = Currency("KRW", "₩")
            
            private val cache = java.util.concurrent.ConcurrentHashMap<String, Currency>()
            
            private val nativeSymbolCache by lazy {
                val map = mutableMapOf<String, String>()
                // Build a cache of shortest native symbols by iterating locales exactly once
                Locale.getAvailableLocales().forEach { locale ->
                    if (locale.country.isNotEmpty()) {
                        runCatching {
                            val javaCurrency = JavaCurrency.getInstance(locale)
                            val code = javaCurrency.currencyCode
                            val symbol = javaCurrency.getSymbol(locale)
                            if (symbol != code) {
                                val existing = map[code]
                                if (existing == null || symbol.length < existing.length) {
                                    map[code] = symbol
                                }
                            }
                        }
                    }
                }
                map
            }

            @JvmStatic
            fun fromCode(code: String): Currency = when {
                code.equals("SAT", ignoreCase = true) ||
                    code.equals("SATS", ignoreCase = true) ||
                    code.equals("BTC", ignoreCase = true) -> BTC
                else -> {
                    val upperCode = code.uppercase(Locale.US)
                    // Fast path for known
                    when (upperCode) {
                        "USD" -> USD
                        "EUR" -> EUR
                        "GBP" -> GBP
                        "JPY" -> JPY
                        "DKK" -> DKK
                        "SEK" -> SEK
                        "NOK" -> NOK
                        "KRW" -> KRW
                        else -> cache.getOrPut(upperCode) {
                            runCatching {
                                val javaCurrency = JavaCurrency.getInstance(upperCode)
                                // Try to find the shortest native symbol from our precomputed cache
                                var symbol = nativeSymbolCache[upperCode] ?: run {
                                    val defaultSym = javaCurrency.getSymbol(Locale.getDefault())
                                    if (defaultSym != upperCode) defaultSym else upperCode
                                }
                                
                                if (symbol == "$" && upperCode != "USD") {
                                    symbol = "${upperCode.take(2)}$"
                                }
                                
                                Currency(upperCode, symbol)
                            }.getOrElse { 
                                // Provide a graceful fallback to a default custom currency structure if Java lacks support
                                Currency(upperCode, upperCode) 
                            }
                        }
                    }
                }
            }

            /** Find currency by its symbol (e.g., "$" -> USD) */
            @JvmStatic
            fun fromSymbol(symbol: String): Currency? {
                if (symbol == "₿") return BTC
                
                // Check known ones first
                val known = listOf(USD, EUR, GBP, JPY, DKK, SEK, NOK, KRW)
                known.find { it.symbol == symbol }?.let { return it }

                // Search through available currencies
                return runCatching {
                    JavaCurrency.getAvailableCurrencies()
                        .mapNotNull { 
                            runCatching { fromCode(it.currencyCode) }.getOrNull()
                        }
                        .find { it.symbol == symbol }
                }.getOrNull()
            }
            
            @JvmStatic
            fun getMatchingCurrencies(prefix: String): List<Currency> {
                if (prefix.isEmpty()) return emptyList()
                
                val knownMatches = listOf(BTC, USD, EUR, GBP, JPY, DKK, SEK, NOK, KRW)
                    .filter { prefix.startsWith(it.symbol) }
                    
                if (knownMatches.isNotEmpty()) {
                    return knownMatches.sortedByDescending { it.symbol.length }
                }
                
                // Fallback to searching all available currencies
                return runCatching {
                    JavaCurrency.getAvailableCurrencies()
                        .mapNotNull { 
                            // Safely convert JavaCurrency back to our Currency class without crashing if invalid
                            runCatching { fromCode(it.currencyCode) }.getOrNull()
                        }
                        .filter { prefix.startsWith(it.symbol) }
                        .sortedByDescending { it.symbol.length }
                }.getOrDefault(emptyList())
            }
        }
    }

    /**
     * Format the amount as a string with the currency symbol, abbreviating large numbers with K/M/B.
     */
    fun toShortString(): String {
        return when {
            currency.isBtc -> {
                // For BTC, value is satoshis. We explicitly abbreviate with K/M/B to avoid confusion with BTC conversion
                val sats = value.toDouble()
                if (sats >= 100_000.0) { // >= 100,000 sats -> 100k
                    "${currency.symbol}${formatAbbreviated(sats, currency.getLocale())}"
                } else {
                    toString()
                }
            }
            else -> {
                val major = value / 100.0
                if (major >= 100_000.0) { // >= 100,000 -> 100k
                    "${currency.symbol}${formatAbbreviated(major, currency.getLocale())}"
                } else {
                    toString()
                }
            }
        }
    }

    private fun formatAbbreviated(number: Double, locale: Locale): String {
        val symbols = DecimalFormatSymbols(locale)
        val formatter = DecimalFormat("#,##0.#", symbols)
        return when {
            number >= 1_000_000_000 -> "${formatter.format(number / 1_000_000_000)}B"
            number >= 1_000_000 -> "${formatter.format(number / 1_000_000)}M"
            number >= 1_000 -> "${formatter.format(number / 1_000)}k"
            else -> formatter.format(number)
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
        return when {
            currency.isBtc -> {
                val formatter = NumberFormat.getNumberInstance(currency.getLocale())
                "${currency.symbol}${formatter.format(value)}"
            }
            currency.isZeroDecimal() -> {
                // Have no decimal places (stored as cents internally, divide by 100)
                val major = value / 100.0
                val formatter = NumberFormat.getIntegerInstance(currency.getLocale())
                "${currency.symbol}${formatter.format(major.toLong())}"
            }
            else -> {
                // 2 decimal places with currency-appropriate separator
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
        return when {
            currency.isBtc -> {
                val formatter = NumberFormat.getNumberInstance(currency.getLocale())
                formatter.format(value)
            }
            currency.isZeroDecimal() -> {
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
            val matchingCurrencies = Currency.getMatchingCurrencies(formatted)
            
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
                when {
                    currency.isBtc -> {
                        // For BTC, value is in satoshis (no decimal conversion needed)
                        val sats = numericPart.replace(",", "").toLong()
                        Amount(sats, currency)
                    }
                    currency.isZeroDecimal() -> {
                        // have no decimal places, but stored as cents internally
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
            return when {
                currency.isBtc -> Amount(majorUnits.toLong(), currency)
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
