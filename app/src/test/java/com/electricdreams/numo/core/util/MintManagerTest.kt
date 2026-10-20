package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.model.AssetId
import com.electricdreams.numo.core.model.UnitId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class MintManagerTest {

    private lateinit var context: Context
    private lateinit var mintManager: MintManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("MintPreferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetSingleton()
        mintManager = MintManager.getInstance(context)
    }

    private fun resetSingleton() {
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", null)
    }

    @Test
    fun `fresh install uses public mints and prefers Minibits`() {
        assertTrue(mintManager.hasAnyMints())
        val mints = mintManager.getAllowedMints()
        assertFalse(mints.isEmpty())
        assertEquals(
            setOf(
                "https://mint.minibits.cash/Bitcoin",
                "https://mint.macadamia.cash",
                "https://antifiat.cash",
                "https://mint.cubabitcoin.org",
            ),
            mints.toSet()
        )
        assertEquals("https://mint.minibits.cash/Bitcoin", mintManager.getPreferredLightningMint())
    }

    @Test
    fun `reset restores public defaults after mint customization`() {
        mintManager.getAllowedMints().forEach { mintManager.removeMint(it) }
        mintManager.addMint("https://custom.mint")

        mintManager.resetToDefaults()

        assertEquals(MintManager.DEFAULT_MINTS, mintManager.getAllowedMints().toSet())
        assertEquals("https://mint.minibits.cash/Bitcoin", mintManager.getPreferredLightningMint())
    }

    @Test
    fun `fresh install enables swaps from unknown mints`() {
        assertTrue(mintManager.isSwapFromUnknownMintsEnabled())
    }

    @Test
    fun `saved mints and disabled swaps survive reinitialization`() {
        mintManager.getAllowedMints().forEach { mintManager.removeMint(it) }
        val customMint = "http://127.0.0.1:3339"
        mintManager.addMint(customMint)
        mintManager.setSwapFromUnknownMintsEnabled(false)

        resetSingleton()
        mintManager = MintManager.getInstance(context)

        assertEquals(listOf(customMint), mintManager.getAllowedMints())
        assertEquals(customMint, mintManager.getPreferredLightningMint())
        assertFalse(mintManager.isSwapFromUnknownMintsEnabled())
    }

    @Test
    fun testAddMint() {
        val newMint = "https://test.mint.url"
        assertTrue(mintManager.addMint(newMint))
        assertTrue(mintManager.isMintAllowed(newMint))
        assertTrue(mintManager.getAllowedMints().contains(newMint))
    }

    @Test
    fun testAddDuplicateMint() {
        val newMint = "https://test.mint.url"
        mintManager.addMint(newMint)
        assertFalse(mintManager.addMint(newMint)) // Should return false
    }

    @Test
    fun testRemoveMint() {
        val mint = "https://mint.minibits.cash/Bitcoin"
        assertTrue(mintManager.isMintAllowed(mint))
        assertTrue(mintManager.removeMint(mint))
        assertFalse(mintManager.isMintAllowed(mint))
    }

    @Test
    fun testPreferredLightningMint() {
        val mint1 = "https://mint.1"
        val mint2 = "https://mint.2"
        
        mintManager.resetToDefaults()
        // Clear defaults for cleaner test?
        // getAllowedMints().forEach { mintManager.removeMint(it) } 
        // Iterate copy to avoid concurrent mod
        ArrayList(mintManager.getAllowedMints()).forEach { mintManager.removeMint(it) }
        
        mintManager.addMint(mint1)
        // First added should be preferred
        assertEquals(mint1, mintManager.getPreferredLightningMint())
        
        mintManager.addMint(mint2)
        // Preferred shouldn't change
        assertEquals(mint1, mintManager.getPreferredLightningMint())
        
        // Change preferred
        mintManager.setPreferredLightningMint(mint2)
        assertEquals(mint2, mintManager.getPreferredLightningMint())
        
        // Remove preferred
        mintManager.removeMint(mint2)
        // Should revert to other available
        assertEquals(mint1, mintManager.getPreferredLightningMint())
    }

    @Test
    fun `selecting mint for saved sats checkout leaves dollar preference unchanged`() {
        mintManager.getAllowedMints().forEach { mintManager.removeMint(it) }
        val mint = "https://mint.minibits.cash/Bitcoin"
        mintManager.addMint(mint)
        mintManager.setMintInfo(mint, """
            {"nuts":{"4":{"methods":[{"method":"bolt11","unit":"sat"}],"disabled":false}}}
        """.trimIndent())
        val previousUnit = mintManager.getPreferredUnit()
        try {
            mintManager.setPreferredUnit("usd")
            assertEquals(mint, mintManager.getPreferredLightningMint("sat"))
            assertEquals(null, mintManager.getPreferredLightningMint())
            assertEquals("usd", mintManager.getPreferredUnit())
        } finally {
            mintManager.setPreferredUnit(previousUnit)
        }
    }

    @Test
    fun testNormalization() {
        val raw = "  mint.test.com/  "
        mintManager.addMint(raw)
        assertTrue(mintManager.isMintAllowed("https://mint.test.com"))
    }

    @Test
    fun `supported units are the canonical union across added mints`() {
        val mintA = "https://mint-a.example"
        val mintB = "https://mint-b.example"
        mintManager.addMint(mintA)
        mintManager.addMint(mintB)
        mintManager.setMintUnits(mintA, listOf("SAT", "points", "auth"))
        mintManager.setMintUnits(mintB, listOf("usd", "POINTS"))

        val supported = mintManager.getSupportedUnits().map { it.value }

        assertEquals(listOf("points", "sat", "usd"), supported)
        assertEquals(listOf(mintA, mintB), mintManager.getMintsSupportingUnit("points").sorted())
        assertFalse(mintManager.mintSupportsUnit(mintA, "usd"))
        val chargeAssets = mintManager.getSupportedChargeAssets()
        assertTrue(chargeAssets.contains(AssetId.global(UnitId.SAT)))
        assertTrue(chargeAssets.contains(AssetId.mintScoped(UnitId.of("points"), mintA)))
        assertTrue(chargeAssets.contains(AssetId.mintScoped(UnitId.of("points"), mintB)))
    }

    @Test
    fun `discovering custom units preserves offline legacy sat mints`() {
        val customMint = "https://custom.example"
        mintManager.addMint(customMint)
        mintManager.setMintUnits(customMint, listOf("points"))

        assertTrue(mintManager.getSupportedUnits().contains(UnitId.SAT))
        assertTrue(mintManager.getSupportedChargeAssets().contains(AssetId.global(UnitId.SAT)))
    }

    @Test
    fun `an explicitly empty unit list does not advertise the preferred unit`() {
        mintManager.getAllowedMints().forEach { mintManager.setMintUnits(it, emptyList()) }

        assertTrue(mintManager.getSupportedUnits().isEmpty())
        assertTrue(mintManager.getSupportedChargeAssets().isEmpty())
    }

    @Test
    fun `unknown metadata only uses legacy sat fallback`() {
        val mint = "https://unknown-metadata.example"
        mintManager.addMint(mint)

        assertTrue(mintManager.mintSupportsUnit(mint, "sat"))
        assertFalse(mintManager.mintSupportsUnit(mint, "points"))
    }
}
