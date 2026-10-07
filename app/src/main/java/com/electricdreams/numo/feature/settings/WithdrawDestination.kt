package com.electricdreams.numo.feature.settings

import java.net.URLDecoder

/**
 * What the merchant typed, pasted or scanned into the withdraw "To" field.
 *
 * One field accepts both Lightning addresses and BOLT11 invoices, so the form
 * reacts to what it detects instead of asking the merchant to pick a format first.
 */
sealed class WithdrawDestination {

    object Empty : WithdrawDestination()

    data class LightningAddress(val address: String) : WithdrawDestination()

    /** [amountSats] is null for invoices that don't encode an amount. */
    data class Invoice(val invoice: String, val amountSats: Long?) : WithdrawDestination()

    data class Invalid(val raw: String) : WithdrawDestination()

    companion object {
        private val INVOICE_PREFIXES = listOf("lnbcrt", "lntbs", "lnbc", "lntb", "lnsb")

        /**
         * Parses raw input. [isValidAddress] decides whether something shaped like
         * `name@domain` is a usable Lightning address (see LightningAddressManager).
         */
        fun parse(input: String, isValidAddress: (String) -> Boolean): WithdrawDestination {
            val cleaned = normalize(input)
            if (cleaned.isEmpty()) return Empty

            val lower = cleaned.lowercase()
            if (INVOICE_PREFIXES.any { lower.startsWith(it) } && lower.lastIndexOf('1') > 2) {
                return Invoice(lower, invoiceAmountSats(lower))
            }
            if (cleaned.contains('@') && isValidAddress(cleaned)) {
                return LightningAddress(cleaned)
            }
            return Invalid(cleaned)
        }

        /** Strips whitespace and URI wrappers (`lightning:`, BIP21 `bitcoin:...?lightning=`). */
        internal fun normalize(input: String): String {
            var value = input.filterNot { it.isWhitespace() }
            if (value.startsWith("bitcoin:", ignoreCase = true)) {
                val query = value.substringAfter('?', missingDelimiterValue = "")
                val lightningParam = query.split('&')
                    .firstOrNull { it.startsWith("lightning=", ignoreCase = true) }
                    ?.substringAfter('=')
                if (lightningParam != null) {
                    // A malformed %-escape leaves the link as-is, which then parses as Invalid.
                    value = try {
                        URLDecoder.decode(lightningParam, "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        value
                    }
                }
            }
            if (value.startsWith("lightning:", ignoreCase = true)) {
                value = value.substring("lightning:".length).removePrefix("//")
            }
            return value
        }

        /**
         * Reads the amount from a BOLT11 human-readable part, e.g. `lnbc2500u1...` is
         * 250,000 sat. Returns null when the invoice carries no amount.
         */
        internal fun invoiceAmountSats(invoice: String): Long? {
            val hrp = invoice.lowercase().substringBeforeLast('1', missingDelimiterValue = "")
            if (!hrp.startsWith("ln")) return null
            val afterPrefix = hrp.removePrefix("ln")
            val amountPart = afterPrefix.dropWhile { it.isLetter() }
            if (amountPart.isEmpty()) return null

            val multiplier = amountPart.last().takeIf { it.isLetter() }
            val digits = if (multiplier != null) amountPart.dropLast(1) else amountPart
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            val value = digits.toBigDecimal()

            // Millisatoshis per unit of the encoded amount.
            val msatPerUnit = when (multiplier) {
                null -> 100_000_000_000.toBigDecimal()
                'm' -> 100_000_000.toBigDecimal()
                'u' -> 100_000.toBigDecimal()
                'n' -> 100.toBigDecimal()
                'p' -> "0.1".toBigDecimal()
                else -> return null
            }
            val msat = value.multiply(msatPerUnit)
            return msat.divideToIntegralValue(1_000.toBigDecimal()).toLong()
        }
    }
}
