package com.electricdreams.numo.feature.insights

import com.electricdreams.numo.core.model.Amount
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object InsightsFormatter {

    private val btcFormat: DecimalFormat by lazy {
        DecimalFormat("0.########", DecimalFormatSymbols(Locale.US))
    }

    fun format(unit: DisplayUnit, sats: Long, fiatMinor: Long, fiatCurrency: Amount.Currency): String =
        when (unit) {
            DisplayUnit.FIAT -> Amount(fiatMinor, fiatCurrency).toString()
            DisplayUnit.SATS -> Amount(sats, Amount.Currency.BTC).toString()
            DisplayUnit.BTC -> formatDecimalBtc(sats)
        }

    private fun formatDecimalBtc(sats: Long): String {
        val btc = sats / 100_000_000.0
        return "₿" + btcFormat.format(btc)
    }
}
