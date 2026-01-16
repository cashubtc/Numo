package com.electricdreams.numo.feature.items.handlers

import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType

/**
 * Handles VAT calculations for both fiat and Bitcoin prices.
 * Provides methods for computing net, gross, and VAT amounts.
 */
object VatCalculator {

    /**
     * Data class representing the results of a VAT calculation.
     */
    data class VatBreakdown(
        val netPrice: String,
        val vatLabel: String,
        val vatAmount: String,
        val grossPrice: String
    )

    /**
     * Calculates VAT breakdown for a fiat price.
     * @param enteredPrice The price entered by the user
     * @param vatRate The VAT rate as an integer (e.g., 19 for 19%)
     * @param priceIncludesVat Whether the entered price includes VAT
     * @param currency The currency code
     * @return VatBreakdown containing formatted price strings
     */
    fun calculateFiatBreakdown(
        enteredPrice: Double,
        vatRate: Int,
        priceIncludesVat: Boolean,
        currency: String
    ): VatBreakdown {
        val netPrice: Double
        val grossPrice: Double
        val vatAmount: Double

        if (priceIncludesVat) {
            // Entered price is gross (includes VAT) - need to calculate net
            grossPrice = enteredPrice
            netPrice = Item.calculateNetFromGross(grossPrice, vatRate.toDouble())
            vatAmount = grossPrice - netPrice
        } else {
            // Entered price is net (excludes VAT) - need to calculate gross
            netPrice = enteredPrice
            grossPrice = Item.calculateGrossFromNet(netPrice, vatRate.toDouble())
            vatAmount = grossPrice - netPrice
        }

        // Format and display in fiat currency
        val currencyObj = Amount.Currency.fromCode(currency)
        return VatBreakdown(
            netPrice = Amount.fromMajorUnits(netPrice, currencyObj).toString(),
            vatLabel = "VAT ($vatRate%)",
            vatAmount = Amount.fromMajorUnits(vatAmount, currencyObj).toString(),
            grossPrice = Amount.fromMajorUnits(grossPrice, currencyObj).toString()
        )
    }

    /**
     * Calculates VAT breakdown for a Bitcoin/sats price.
     * @param enteredSats The price in sats entered by the user
     * @param vatRate The VAT rate as an integer (e.g., 19 for 19%)
     * @param priceIncludesVat Whether the entered price includes VAT
     * @return VatBreakdown containing formatted price strings
     */
    fun calculateSatsBreakdown(
        enteredSats: Long,
        vatRate: Int,
        priceIncludesVat: Boolean
    ): VatBreakdown {
        val netSats: Long
        val grossSats: Long
        val vatSats: Long

        if (priceIncludesVat) {
            // Entered price is gross (includes VAT) - need to calculate net
            grossSats = enteredSats
            netSats = kotlin.math.round(Item.calculateNetFromGross(grossSats.toDouble(), vatRate.toDouble())).toLong()
            vatSats = grossSats - netSats
        } else {
            // Entered price is net (excludes VAT) - need to calculate gross
            netSats = enteredSats
            grossSats = kotlin.math.round(Item.calculateGrossFromNet(netSats.toDouble(), vatRate.toDouble())).toLong()
            vatSats = grossSats - netSats
        }

        // Format and display in sats
        return VatBreakdown(
            netPrice = Amount(netSats, Amount.Currency.BTC).toString(),
            vatLabel = "VAT ($vatRate%)",
            vatAmount = Amount(vatSats, Amount.Currency.BTC).toString(),
            grossPrice = Amount(grossSats, Amount.Currency.BTC).toString()
        )
    }

    /**
     * Calculates the net price from an entered price, considering VAT settings.
     * @param enteredPrice The price entered by the user (fiat)
     * @param vatEnabled Whether VAT is enabled
     * @param priceIncludesVat Whether the entered price includes VAT
     * @param vatRate The VAT rate as an integer
     * @return The net price (excluding VAT) to be stored
     */
    fun calculateNetPriceForStorage(
        enteredPrice: Double,
        vatEnabled: Boolean,
        priceIncludesVat: Boolean,
        vatRate: Int
    ): Double {
        return if (vatEnabled && priceIncludesVat) {
            // Entered price includes VAT - calculate net price
            Item.calculateNetFromGross(enteredPrice, vatRate.toDouble())
        } else {
            // Entered price is net price (or no VAT)
            enteredPrice
        }
    }
}
