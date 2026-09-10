package com.electricdreams.numo.feature.onboarding

/** Fixed, illustrative USD prices. Never reads exchange rates or merchant balances. */
internal object OnboardingAmounts {
    const val BITCOIN_PRICE_USD_CENTS = 7_854_400L
    val productCents = listOf(350L, 2500L, 1200L)
    val checkoutCents = listOf(0L, 3L, 35L, 350L)
    val salesCents = listOf(18450L, 21800L, 24750L)
    val withdrawalCents = listOf(4750L, 7125L, 9500L, 6650L, 5225L, 8075L, 5700L)

    fun sats(usdCents: Long): Long =
        (usdCents * 100_000_000L + BITCOIN_PRICE_USD_CENTS / 2) / BITCOIN_PRICE_USD_CENTS
}
