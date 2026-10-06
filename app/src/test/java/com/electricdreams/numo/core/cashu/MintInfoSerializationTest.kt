package com.electricdreams.numo.core.cashu

import com.fasterxml.jackson.databind.ObjectMapper
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MeltMethodSettings
import org.cashudevkit.MintInfo
import org.cashudevkit.MintMethodSettings
import org.cashudevkit.Nut04Settings
import org.cashudevkit.Nut05Settings
import org.cashudevkit.Nuts
import org.cashudevkit.PaymentMethod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

import com.electricdreams.numo.core.util.MintLimitChecker

@RunWith(RobolectricTestRunner::class)
class MintInfoSerializationTest {

    @Test
    fun `CDK bounds serialize as numbers and round trip through both limit parsers`() {
        val serialized = CashuWalletManager.mintInfoToJson(mintInfo())

        for (method in serializedMethods(serialized)) {
            assertTrue(method.get("min_amount") is Number)
            assertTrue(method.get("max_amount") is Number)
            assertEquals(1L, method.getLong("min_amount"))
            assertEquals(500_000L, method.getLong("max_amount"))
        }

        for (parsed in parsedLimits(serialized)) {
            val limits = requireNotNull(parsed)
            assertEquals(1, limits.mintMethods.size)
            assertEquals(1, limits.meltMethods.size)
            for (method in limits.mintMethods + limits.meltMethods) {
                assertEquals(1L, method.minAmount)
                assertEquals(500_000L, method.maxAmount)
                assertEquals("sat", method.unit)
            }
            val withinLimits = MintLimitChecker.checkMintLimits(100, limits)
            assertTrue(withinLimits.isBolt11Supported)
            assertTrue(withinLimits.isValid)
            assertFalse(MintLimitChecker.checkMintLimits(500_001, limits).isValid)
        }
    }

    @Test
    fun `absent CDK bounds stay absent in JSON and parsed limits`() {
        val serialized = CashuWalletManager.mintInfoToJson(mintInfo(null, null))

        for (method in serializedMethods(serialized)) {
            assertFalse(method.has("min_amount"))
            assertFalse(method.has("max_amount"))
        }
        for (parsed in parsedLimits(serialized)) {
            val limits = requireNotNull(parsed)
            for (method in limits.mintMethods + limits.meltMethods) {
                assertNull(method.minAmount)
                assertNull(method.maxAmount)
            }
            assertTrue(MintLimitChecker.checkMintLimits(100, limits).isBolt11Supported)
        }
    }

    @Test
    fun `large signed bounds round trip without floating point rounding`() {
        val minAmount = 9_007_199_254_740_993uL
        val maxAmount = Long.MAX_VALUE.toULong()
        val serialized = CashuWalletManager.mintInfoToJson(mintInfo(minAmount, maxAmount))

        for (parsed in parsedLimits(serialized)) {
            val limits = requireNotNull(parsed)
            for (method in limits.mintMethods + limits.meltMethods) {
                assertEquals(minAmount.toLong(), method.minAmount)
                assertEquals(Long.MAX_VALUE, method.maxAmount)
            }
        }
    }

    @Test
    fun `unsigned bounds serialize as exact positive JSON numbers`() {
        val minAmount = Long.MAX_VALUE.toULong() + 1uL
        val maxAmount = ULong.MAX_VALUE
        val serialized = CashuWalletManager.mintInfoToJson(mintInfo(minAmount, maxAmount))
        val nuts = ObjectMapper().readTree(serialized).get("nuts")

        for (nut in listOf("4", "5")) {
            val method = nuts.get(nut).get("methods").get(0)
            val min = method.get("min_amount")
            val max = method.get("max_amount")
            assertTrue(min.isIntegralNumber)
            assertTrue(max.isIntegralNumber)
            assertEquals(minAmount.toString().toBigInteger(), min.bigIntegerValue())
            assertEquals(maxAmount.toString().toBigInteger(), max.bigIntegerValue())
        }
    }

    private fun serializedMethods(serialized: String): List<JSONObject> {
        val nuts = JSONObject(serialized).getJSONObject("nuts")
        return listOf("4", "5").map { nut ->
            nuts.getJSONObject(nut).getJSONArray("methods").getJSONObject(0)
        }
    }

    private fun parsedLimits(serialized: String): List<CashuWalletManager.MintLimits?> = listOf(
        CashuWalletManager.mintInfoFromJson(serialized)?.mintLimits,
        CashuWalletManager.extractMintLimitsFromJson(serialized),
    )

    private fun mintInfo(minAmount: ULong? = 1uL, maxAmount: ULong? = 500_000uL): MintInfo {
        val mintMethod = MintMethodSettings(
            method = PaymentMethod.Bolt11,
            unit = CurrencyUnit.Sat,
            methodName = null,
            minAmount = minAmount?.let { CdkAmount(it) },
            maxAmount = maxAmount?.let { CdkAmount(it) },
            description = null,
        )
        val meltMethod = MeltMethodSettings(
            method = PaymentMethod.Bolt11,
            unit = CurrencyUnit.Sat,
            methodName = null,
            minAmount = minAmount?.let { CdkAmount(it) },
            maxAmount = maxAmount?.let { CdkAmount(it) },
            amountless = null,
        )
        val nuts = mock<Nuts>()
        whenever(nuts.nut04).thenReturn(Nut04Settings(listOf(mintMethod), false))
        whenever(nuts.nut05).thenReturn(Nut05Settings(listOf(meltMethod), false))
        val info = mock<MintInfo>()
        whenever(info.nuts).thenReturn(nuts)
        return info
    }
}
