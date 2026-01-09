package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
