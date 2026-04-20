package com.electricdreams.numo.core.util

import com.electricdreams.numo.core.cashu.CashuWalletManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MintLimitCheckerTest {

    @Test
    fun `given null mintLimits, when checkMintLimits called, then reject with DISABLED`() {
        val result = MintLimitChecker.checkMintLimits(1000, null)
        assertFalse(result.isValid)
        assertEquals(null, result.minAmount)
        assertEquals(null, result.maxAmount)
        assertEquals(MintLimitChecker.LimitType.DISABLED, result.limitType)
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
        assertFalse(result.isValid)
        assertEquals(MintLimitChecker.LimitType.DISABLED, result.limitType)
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
    fun `given mint with disabled true, when checkMintLimits called, then reject with DISABLED`() {
        val mintLimits = CashuWalletManager.MintLimits(
            mintMethods = listOf(
                CashuWalletManager.MintMethodSettings(
                    method = "bolt11",
                    unit = "sat",
                    minAmount = 100,
                    maxAmount = 10000,
                    disabled = true
                )
            ),
            meltMethods = emptyList()
        )
        val result = MintLimitChecker.checkMintLimits(5000, mintLimits)
        assertFalse(result.isValid)
        assertEquals(MintLimitChecker.LimitType.DISABLED, result.limitType)
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
}