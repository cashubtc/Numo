package com.electricdreams.numo.core.util

import com.electricdreams.numo.core.cashu.CashuWalletManager.MintLimits
import com.electricdreams.numo.core.cashu.CashuWalletManager.MintMethodSettings
import com.electricdreams.numo.core.model.UnitId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MintCapabilitiesTest {
    private val mint = "https://mint.example"
    private val points = UnitId.of("points")

    @Test
    fun `capabilities distinguish issuer unit operation and method`() {
        val capabilities = MintCapabilities(mint, MintLimits(
            mintMethods = listOf(MintMethodSettings("bolt12", "sat", null, null)),
            meltMethods = listOf(MintMethodSettings("bolt11", "POINTS", 10, 100)),
        ))
        assertNull(capabilities.find(UnitId.SAT, MintOperation.MINT, "bolt11"))
        assertNotNull(capabilities.find(UnitId.SAT, MintOperation.MINT, "bolt12"))
        assertNull(capabilities.find(points, MintOperation.MINT, "bolt11"))
        assertNull(capabilities.find(UnitId.SAT, MintOperation.MELT, "bolt11"))
        val melt = requireNotNull(capabilities.find(points, MintOperation.MELT, "bolt11"))
        assertEquals(mint, melt.mintUrl)
        assertFalse(melt.allowsAmount(9))
        assertTrue(melt.allowsAmount(10))
        assertTrue(melt.allowsAmount(100))
        assertFalse(melt.allowsAmount(101))
        assertNull(MintCapabilities("https://other.example", null)
            .find(points, MintOperation.MELT, "bolt11"))
    }

    @Test
    fun `missing disabled and reserved capabilities are unavailable`() {
        val capabilities = MintCapabilities(mint, MintLimits(mintMethods = listOf(
            MintMethodSettings("bolt11", "sat", null, null, disabled = true),
            MintMethodSettings("bolt11", "auth", null, null),
        )))
        assertNull(capabilities.find(UnitId.SAT, MintOperation.MINT, "bolt11"))
        assertNull(capabilities.find(UnitId.AUTH, MintOperation.MINT, "bolt11"))
        assertNull(capabilities.find(UnitId.of("usd"), MintOperation.MINT, "bolt11"))
    }

    @Test
    fun `custom methods are matched by identifier rather than bolt11 substring`() {
        val capabilities = MintCapabilities(mint, MintLimits(mintMethods = listOf(
            MintMethodSettings("my-bolt11-method", "sat", null, null),
        )))
        assertNull(capabilities.find(UnitId.SAT, MintOperation.MINT, "bolt11"))
        assertNotNull(capabilities.find(UnitId.SAT, MintOperation.MINT, "my-bolt11-method"))
    }

    @Test
    fun `legacy CDK cache and sats alias still resolve`() {
        val capabilities = MintCapabilities(mint, MintLimits(mintMethods = listOf(
            MintMethodSettings("org.cashudevkit.PaymentMethod\$Bolt11@ab12", "sats", null, null),
        )))
        assertNotNull(capabilities.find(UnitId.SAT, MintOperation.MINT, "bolt11"))
    }
}
