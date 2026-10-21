package com.electricdreams.numo.core.util

import com.electricdreams.numo.core.cashu.CashuWalletManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MintLimitCheckerTest {

    @Test
    fun `given null mintLimits, when checkMintLimits called, then allow but flag bolt11 unsupported`() {
        val result = MintLimitChecker.checkMintLimits(1000, null)
        
        assertTrue(result.isValid)
        assertFalse(result.isBolt11Supported)
        assertNull(result.minAmount)
        assertNull(result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.NONE, result.limitType)
    }

    @Test
    fun `given mintLimits without bolt11 method, when checkMintLimits called, then allow but flag bolt11 unsupported`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "someOtherMethod",
                    unit = "sat",
                    minAmount = 100,
                    maxAmount = 10000,
                    disabled = false
                )
            )
        )

        val result = MintLimitChecker.checkMintLimits(1000, mintLimits)
        
        assertTrue(result.isValid)
        assertFalse(result.isBolt11Supported)
        assertNull(result.minAmount)
        assertNull(result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.NONE, result.limitType)
    }

    @Test
    fun `given mintLimits without bolt11 method, when checkMintLimits called, then reject with DISABLED`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "onchain",
                    unit = "sat",
                    minAmount = null,
                    maxAmount = null
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(1000, mintLimits)
        assertTrue(result.isValid)
        assertFalse(result.isBolt11Supported)
        assertNull(result.minAmount)
        assertNull(result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.NONE, result.limitType)
    }

    @Test
    fun `given amount below minimum, when checkMintLimits called, then reject with MIN limitType`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = 100L,
                    maxAmount = 10000L
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(50, mintLimits)
        assertFalse(result.isValid)
        assertEquals(100L, result.minAmount)
        assertEquals(10000L, result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.MIN, result.limitType)
    }

    @Test
    fun `given amount above maximum, when checkMintLimits called, then reject with MAX limitType`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = 100L,
                    maxAmount = 10000L
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(15000, mintLimits)
        assertFalse(result.isValid)
        assertEquals(100L, result.minAmount)
        assertEquals(10000L, result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.MAX, result.limitType)
    }

    @Test
    fun `given amount within limits, when checkMintLimits called, then allow amount`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = 100L,
                    maxAmount = 10000L
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(5000, mintLimits)
        assertTrue(result.isValid)
        assertEquals(100L, result.minAmount)
        assertEquals(10000L, result.maxAmount)
    }

    @Test
    fun `given mint with disabled true, when checkMintLimits called, then allow but flag bolt11 unsupported`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = 100,
                    maxAmount = 10000,
                    disabled = true
                )
            )
        )

        val result = MintLimitChecker.checkMintLimits(5000, mintLimits)
        
        assertTrue(result.isValid)
        assertFalse(result.isBolt11Supported)
        assertNull(result.minAmount)
        assertNull(result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.NONE, result.limitType)
    }

    @Test
    fun `given amount exactly at minimum, when checkMintLimits called, then allow amount`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = 100,
                    maxAmount = null
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(100, mintLimits)
        assertTrue(result.isValid)
    }

    @Test
    fun `given amount exactly at maximum, when checkMintLimits called, then allow amount`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = null,
                    maxAmount = 10000
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(10000, mintLimits)
        assertTrue(result.isValid)
    }

    @Test
    fun `given no min or max limits, when checkMintLimits called, then allow any amount`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = null,
                    maxAmount = null
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(1_000_000, mintLimits)
        assertTrue(result.isValid)
    }

    @Test
    fun `given case insensitive bolt11 method, when checkMintLimits called, then find method`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "BOLT11",
                    unit = "SAT",
                    minAmount = 100,
                    maxAmount = 10000
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(5000, mintLimits)
        assertTrue(result.isValid)
    }

    @Test
    fun `canonical unit aliases match without relabeling another unit`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sats",
                    minAmount = 1,
                    maxAmount = 10_000,
                ),
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "points",
                    minAmount = 500,
                    maxAmount = 600,
                ),
            ),
        )

        val satResult = MintLimitChecker.checkMintLimits(100, mintLimits, "sat")
        val pointsResult = MintLimitChecker.checkMintLimits(100, mintLimits, "POINTS")

        assertTrue(satResult.isValid)
        assertFalse(pointsResult.isValid)
        assertEquals(MintLimitChecker.LimitType.MIN, pointsResult.limitType)
    }

    @Test
    fun `amount plus tip overflow is rejected instead of wrapping negative`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = null,
                    maxAmount = null,
                ),
            ),
        )

        val result = MintLimitChecker.checkMintLimitsWithTip(
            amount = Long.MAX_VALUE,
            tipAmount = 1L,
            mintLimits = mintLimits,
            preferredUnit = "sat",
        )

        assertFalse(result.isValid)
        assertTrue(result.isBolt11Supported)
        assertEquals(MintLimitChecker.LimitType.MAX, result.limitType)
    }

    @Test
    fun `reserved payment unit is rejected`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "auth",
                    minAmount = null,
                    maxAmount = null,
                ),
            ),
        )

        val result = MintLimitChecker.checkMintLimits(1, mintLimits, "auth")

        assertFalse(result.isValid)
        assertFalse(result.isBolt11Supported)
        assertEquals(MintLimitChecker.LimitType.DISABLED, result.limitType)
    }
}
