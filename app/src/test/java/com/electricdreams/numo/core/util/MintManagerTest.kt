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
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class MintManagerTest {

    private lateinit var context: Context
    private lateinit var mintManager: MintManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSingleton()
        mintManager = MintManager.getInstance(context)
        mintManager.resetToDefaults()
    }

    private fun resetSingleton() {
        val instance = MintManager.Companion
        val clazz = instance::class.java
        try {
            var field: Field? = null
            var currentClass: Class<*>? = clazz
            while (currentClass != null) {
                try {
                    field = currentClass.getDeclaredField("instance")
                    break
                } catch (e: NoSuchFieldException) {
                    currentClass = currentClass.superclass
                }
            }
            if (field != null) {
                field.isAccessible = true
                field.set(instance, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Test
    fun testDefaultMints() {
        assertTrue(mintManager.hasAnyMints())
        val mints = mintManager.getAllowedMints()
        assertFalse(mints.isEmpty())
        assertTrue(mints.contains("https://mint.minibits.cash/Bitcoin"))
        assertTrue(mints.contains("https://mint.macadamia.cash"))
        assertTrue(mints.contains("https://antifiat.cash"))
        assertFalse(mints.contains("https://mint.chorus.community"))
        assertFalse(mints.contains("https://mint.coinos.io"))
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
    fun `unknown metadata only uses legacy sat fallback`() {
        val mint = "https://unknown-metadata.example"
        mintManager.addMint(mint)

        assertTrue(mintManager.mintSupportsUnit(mint, "sat"))
        assertFalse(mintManager.mintSupportsUnit(mint, "points"))
    }
}
