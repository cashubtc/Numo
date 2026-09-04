package com.electricdreams.numo.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitFeaturePolicyTest {

    @Test
    fun `bolt11 automation is disabled for fully custom and reserved units`() {
        assertFalse(UnitFeaturePolicy.supportsUnknownMintSwap(UnitId.of("points")))
        assertFalse(UnitFeaturePolicy.supportsAutoWithdraw(UnitId.of("points")))
        assertFalse(UnitFeaturePolicy.supportsAutoWithdraw(UnitId.AUTH))
    }

    @Test
    fun `existing standard units retain automation support`() {
        assertTrue(UnitFeaturePolicy.supportsUnknownMintSwap(UnitId.SAT))
        assertTrue(UnitFeaturePolicy.supportsAutoWithdraw(UnitId.of("usd")))
    }
}
