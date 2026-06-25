package com.electricdreams.numo.feature.insights

import com.electricdreams.numo.core.model.Amount

object InsightsFormatter {

    fun format(unit: DisplayUnit, sats: Long, fiatMinor: Long, fiatCurrency: Amount.Currency): String =
        if (fiatCurrency.isBtc) {
            when (unit) {
                DisplayUnit.FIAT -> Amount(fiatMinor, fiatCurrency).toString()
                DisplayUnit.SATS -> Amount(sats, Amount.Currency.BTC).toString()
            }
        } else {
            // Under custom unit, always display using the custom currency (since there are no real SATS)
            Amount(fiatMinor, fiatCurrency).toString()
        }
}
