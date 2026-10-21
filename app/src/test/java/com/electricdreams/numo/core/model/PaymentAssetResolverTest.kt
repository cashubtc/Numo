package com.electricdreams.numo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentAssetResolverTest {

    private val mintA = "https://mint-a.example"
    private val mintB = "https://mint-b.example"
    private val points = UnitId.of("points")

    @Test
    fun `custom unit requires its exact supported issuer`() {
        val supported = listOf(
            AssetId.mintScoped(points, mintA),
            AssetId.mintScoped(points, mintB),
        )

        assertEquals(
            PaymentAssetResolver.Result.Resolved(AssetId.mintScoped(points, mintA)),
            PaymentAssetResolver.resolve("POINTS", mintA, supported),
        )
        assertEquals(
            PaymentAssetResolver.Result.Rejected(
                PaymentAssetResolver.RejectionReason.MISSING_CUSTOM_ISSUER,
            ),
            PaymentAssetResolver.resolve("points", null, supported),
        )
        assertEquals(
            PaymentAssetResolver.Result.Rejected(
                PaymentAssetResolver.RejectionReason.UNSUPPORTED_ASSET,
            ),
            PaymentAssetResolver.resolve("points", "https://other.example", supported),
        )
    }

    @Test
    fun `standard unit ignores issuer and resolves globally`() {
        val supported = listOf(AssetId.global(UnitId.of("usd")))

        assertEquals(
            PaymentAssetResolver.Result.Resolved(AssetId.global(UnitId.of("usd"))),
            PaymentAssetResolver.resolve(
                rawUnit = "USD",
                rawIssuerScope = mintA,
                supportedAssets = supported,
            ),
        )
    }

    @Test
    fun `invalid reserved and unavailable units are rejected`() {
        val supported = listOf(AssetId.global(UnitId.SAT))

        assertEquals(
            PaymentAssetResolver.Result.Rejected(
                PaymentAssetResolver.RejectionReason.INVALID_UNIT,
            ),
            PaymentAssetResolver.resolve("bad unit", null, supported),
        )
        assertEquals(
            PaymentAssetResolver.Result.Rejected(
                PaymentAssetResolver.RejectionReason.RESERVED_UNIT,
            ),
            PaymentAssetResolver.resolve("auth", null, supported),
        )
        assertEquals(
            PaymentAssetResolver.Result.Rejected(
                PaymentAssetResolver.RejectionReason.UNSUPPORTED_ASSET,
            ),
            PaymentAssetResolver.resolve("eur", null, supported),
        )
    }

    @Test
    fun `custom issuer comparison never aliases two mints`() {
        val result = PaymentAssetResolver.resolve(
            rawUnit = "points",
            rawIssuerScope = mintB,
            supportedAssets = listOf(AssetId.mintScoped(points, mintA)),
        )

        assertTrue(result is PaymentAssetResolver.Result.Rejected)
    }
}
