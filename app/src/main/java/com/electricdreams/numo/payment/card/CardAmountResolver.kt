package com.electricdreams.numo.payment.card

import com.electricdreams.numo.core.model.Amount
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.Currency as JavaCurrency

/**
 * Converts a Numo checkout amount into the fiat amount charged to a card.
 *
 * Card payments are charged in the merchant's fiat currency (Numo's display currency).
 * When the sale was entered in that currency the entered amount is charged exactly;
 * otherwise the satoshi total is converted with the current Bitcoin price.
 */
object CardAmountResolver {

    /** A card charge in Stripe's representation: smallest currency unit and lowercase ISO code. */
    data class CardAmount(val minorUnits: Long, val currency: String)

    /**
     * @param enteredAmount the parsed checkout amount as displayed to the customer, if known.
     * @param amountSats the checkout total in satoshis.
     * @param currencyCode the merchant's fiat currency (ISO 4217, e.g. "USD").
     * @param btcPrice the price of one bitcoin in [currencyCode], or 0 when unknown.
     * @return the amount to charge, or null when it cannot be determined.
     */
    fun resolve(
        enteredAmount: Amount?,
        amountSats: Long,
        currencyCode: String,
        btcPrice: Double,
    ): CardAmount? {
        val code = currencyCode.uppercase(Locale.US)
        val stripeDigits = isoFractionDigits(code) ?: return null

        val minorUnits = if (
            enteredAmount != null &&
            !enteredAmount.currency.isBtc &&
            enteredAmount.currency.name.equals(code, ignoreCase = true)
        ) {
            val numoDigits = if (enteredAmount.currency.isZeroDecimal()) 0 else 2
            BigDecimal.valueOf(enteredAmount.value)
                .movePointLeft(numoDigits)
                .movePointRight(stripeDigits)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        } else {
            if (btcPrice <= 0.0 || amountSats <= 0) return null
            BigDecimal.valueOf(amountSats)
                .multiply(BigDecimal.valueOf(btcPrice))
                .movePointLeft(SATS_DECIMALS)
                .movePointRight(stripeDigits)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        }

        if (minorUnits <= 0) return null
        return CardAmount(minorUnits, code.lowercase(Locale.US))
    }

    private fun isoFractionDigits(code: String): Int? = runCatching {
        JavaCurrency.getInstance(code).defaultFractionDigits
    }.getOrNull()?.takeIf { it >= 0 }

    private const val SATS_DECIMALS = 8
}
