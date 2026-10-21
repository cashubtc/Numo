package com.electricdreams.numo.core.model

import java.util.Locale

/**
 * Canonical Cashu unit identifier used at protocol and persistence boundaries.
 *
 * Cashu unit identifiers are treated as case-insensitive throughout Numo. The canonical value is
 * therefore lower-case using [Locale.ROOT]. Display casing belongs in [UnitDescriptor], never in
 * equality checks or wallet lookups.
 */
@JvmInline
value class UnitId private constructor(val value: String) {

    val isSat: Boolean
        get() = this == SAT

    val isReserved: Boolean
        get() = this == AUTH

    override fun toString(): String = value

    companion object {
        val SAT = UnitId("sat")
        val MSAT = UnitId("msat")
        val BTC = UnitId("btc")
        val AUTH = UnitId("auth")

        /**
         * Parse and canonicalize a unit identifier.
         *
         * Unit identifiers are machine-readable identifiers, so embedded whitespace and control
         * characters are rejected. The legacy UI alias `sats` is normalized to the Cashu `sat`
         * unit.
         */
        @JvmStatic
        fun of(rawValue: String): UnitId {
            val trimmed = rawValue.trim()
            require(trimmed.isNotEmpty()) { "Unit identifier cannot be blank" }
            require(trimmed.none { it.isWhitespace() || it.isISOControl() }) {
                "Unit identifier cannot contain whitespace or control characters"
            }

            val canonical = trimmed.lowercase(Locale.ROOT)
            return when (canonical) {
                "sat", "sats" -> SAT
                "msat" -> MSAT
                "btc" -> BTC
                "auth" -> AUTH
                else -> UnitId(canonical)
            }
        }

        @JvmStatic
        fun ofOrNull(rawValue: String?): UnitId? =
            rawValue?.let { runCatching { of(it) }.getOrNull() }
    }
}
