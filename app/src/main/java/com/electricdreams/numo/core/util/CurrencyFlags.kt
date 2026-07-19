package com.electricdreams.numo.core.util

import android.annotation.SuppressLint
import android.content.Context
import java.util.Locale

/**
 * Maps ISO 4217 currency codes to the bundled flag_xx drawables
 * (see scripts/generate_flag_assets.sh).
 */
object CurrencyFlags {

    // ISO 4217 rule: the first two letters of a currency code are the ISO 3166
    // country code. These are the codes where that rule doesn't hold.
    private val OVERRIDES = mapOf(
        "EUR" to "eu", // supranational
        "MLC" to "cu", // Cuban MLC, non-ISO code
        "ANG" to "cw", // Netherlands Antilles dissolved; guilder still used by Curaçao
    )

    private val cache = HashMap<String, Int>()

    /**
     * @return drawable resource id for the currency's flag, or 0 when there is
     * no single-country flag (e.g. XAU/XOF) — callers should show a fallback.
     */
    @SuppressLint("DiscouragedApi")
    fun flagResId(context: Context, currencyCode: String): Int {
        val code = currencyCode.uppercase(Locale.ROOT)
        return cache.getOrPut(code) {
            // X-prefixed codes are supranational or commodities (XOF, XAU, ...)
            val country = OVERRIDES[code]
                ?: code.takeIf { it.length == 3 && it[0] != 'X' }
                    ?.substring(0, 2)
                    ?.lowercase(Locale.ROOT)
                ?: return@getOrPut 0
            context.resources.getIdentifier("flag_$country", "drawable", context.packageName)
        }
    }
}
