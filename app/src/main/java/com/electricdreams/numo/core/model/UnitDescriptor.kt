package com.electricdreams.numo.core.model

import java.util.Currency
import java.util.Locale

enum class UnitKind {
    BITCOIN,
    ISO_4217,
    STABLECOIN,
    CUSTOM,
    RESERVED,
}

/**
 * Display metadata for an atomic Cashu unit.
 *
 * This metadata never participates in amount equality. Fully custom units use whole atomic
 * values until standardized Cashu unit metadata is supported; their precision is not configurable.
 */
data class UnitDescriptor(
    val unit: UnitId,
    val displayCode: String,
    val symbol: String,
    val fractionDigits: Int,
    val kind: UnitKind,
) {
    init {
        require(displayCode.isNotBlank()) { "Display code cannot be blank" }
        require(fractionDigits in 0..18) { "Fraction digits must be between 0 and 18" }
        require(kind != UnitKind.CUSTOM || fractionDigits == 0) {
            "Custom units must use whole numbers until standardized unit metadata is supported"
        }
    }

    val requiresIssuerScope: Boolean
        get() = kind == UnitKind.CUSTOM

    companion object {
        // Android's getInstance(String) also accepts unknown three-letter codes and assigns
        // two fraction digits. Only the supported currency list identifies actual ISO units.
        private val isoCurrencies by lazy {
            Currency.getAvailableCurrencies().associateBy { it.currencyCode }
        }

        private val stablecoinFractionDigits = mapOf(
            "usdt" to 2,
            "usdc" to 2,
            "eurc" to 2,
            "gyen" to 0,
        )

        @JvmStatic
        fun defaultFor(unit: UnitId, locale: Locale = Locale.getDefault()): UnitDescriptor {
            return when (unit) {
                UnitId.SAT -> UnitDescriptor(unit, "sat", "sat", 0, UnitKind.BITCOIN)
                UnitId.MSAT -> UnitDescriptor(unit, "msat", "msat", 0, UnitKind.BITCOIN)
                UnitId.BTC -> UnitDescriptor(unit, "BTC", "BTC", 8, UnitKind.BITCOIN)
                UnitId.AUTH -> UnitDescriptor(unit, "auth", "auth", 0, UnitKind.RESERVED)
                else -> fromStablecoin(unit) ?: fromIso4217(unit, locale) ?: custom(unit)
            }
        }

        @JvmStatic
        fun custom(
            unit: UnitId,
            displayCode: String = unit.value.uppercase(Locale.ROOT),
            symbol: String = displayCode,
        ): UnitDescriptor = UnitDescriptor(
            unit = unit,
            displayCode = displayCode,
            symbol = symbol,
            fractionDigits = 0,
            kind = UnitKind.CUSTOM,
        )

        private fun fromStablecoin(unit: UnitId): UnitDescriptor? {
            val fractionDigits = stablecoinFractionDigits[unit.value] ?: return null
            val code = unit.value.uppercase(Locale.ROOT)
            return UnitDescriptor(unit, code, code, fractionDigits, UnitKind.STABLECOIN)
        }

        private fun fromIso4217(unit: UnitId, locale: Locale): UnitDescriptor? {
            val currency = isoCurrencies[unit.value.uppercase(Locale.ROOT)] ?: return null
            val fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: return null
            return UnitDescriptor(
                unit = unit,
                displayCode = currency.currencyCode,
                symbol = currency.getSymbol(locale),
                fractionDigits = fractionDigits,
                kind = UnitKind.ISO_4217,
            )
        }
    }
}
