package com.electricdreams.numo.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OnboardingAmountsTest {
    @Test
    fun `illustrative dollars convert at 78544 per bitcoin rounded to the nearest sat`() {
        assertEquals(100_000_000L, OnboardingAmounts.sats(7_854_400L))
        assertEquals(listOf(4456L, 31829L, 15278L),
            OnboardingAmounts.productCents.map(OnboardingAmounts::sats))
        assertEquals(listOf(0L, 38L, 446L, 4456L),
            OnboardingAmounts.checkoutCents.map(OnboardingAmounts::sats))
    }

    @Test
    fun `all story amounts retain their fiat value to less than half a sat`() {
        val cents = OnboardingAmounts.productCents + OnboardingAmounts.checkoutCents +
            OnboardingAmounts.salesCents + OnboardingAmounts.withdrawalCents
        cents.forEach {
            val exactSats = it * 100_000_000.0 / OnboardingAmounts.BITCOIN_PRICE_USD_CENTS
            assertTrue(abs(OnboardingAmounts.sats(it) - exactSats) <= .5)
        }
    }
}
