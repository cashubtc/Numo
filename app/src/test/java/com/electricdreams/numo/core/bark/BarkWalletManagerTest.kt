package com.electricdreams.numo.core.bark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.prefs.PreferenceStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BarkWalletManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset wallet state
        BarkWalletManager.disableWallet()
    }

    @Test
    fun testBarkWalletManagerEnabledState() {
        BarkWalletManager.init(context)
        val prefs = PreferenceStore.app(context)

        // Default should be false
        prefs.putBoolean("bark_enabled", false)
        assertFalse(BarkWalletManager.isEnabled())

        // Set to true
        prefs.putBoolean("bark_enabled", true)
        assertTrue(BarkWalletManager.isEnabled())
    }

    @Test
    fun testDisableWalletSetsInstanceToNull() {
        BarkWalletManager.disableWallet()
        // No crash and state resets correctly
        assertFalse(BarkWalletManager.isEnabled())
    }
}
