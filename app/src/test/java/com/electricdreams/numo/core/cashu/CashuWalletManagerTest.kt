package com.electricdreams.numo.core.cashu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.cashu.CashuWalletManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class CashuWalletManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        setPrivateField("wallet", null)
        setPrivateField("database", null)
    }

    private fun setPrivateField(fieldName: String, value: Any?) {
        try {
            val instance = CashuWalletManager
            val field = instance::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(instance, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Test
    fun testMintInfoSerialization() {
        val jsonString = """
            {
                "name": "Test Mint",
                "description": "A test mint",
                "descriptionLong": "Long description",
                "motd": "Hello World",
                "iconUrl": "http://example.com/icon.png",
                "version": {
                    "name": "Nutshell",
                    "version": "1.0.0"
                },
                "contact": [
                    { "method": "email", "info": "admin@example.com" }
                ]
            }
        """.trimIndent()

        val cachedInfo = CashuWalletManager.mintInfoFromJson(jsonString)
        assertNotNull(cachedInfo)
        assertEquals("Test Mint", cachedInfo?.name)
        assertEquals("A test mint", cachedInfo?.description)
        assertEquals("Long description", cachedInfo?.descriptionLong)
        assertEquals("Hello World", cachedInfo?.motd)
        assertEquals("http://example.com/icon.png", cachedInfo?.iconUrl)
        
        assertNotNull(cachedInfo?.versionInfo)
        assertEquals("Nutshell", cachedInfo?.versionInfo?.name)
        assertEquals("1.0.0", cachedInfo?.versionInfo?.version)
        
        assertEquals(1, cachedInfo?.contact?.size)
        assertEquals("email", cachedInfo?.contact?.get(0)?.method)
        assertEquals("admin@example.com", cachedInfo?.contact?.get(0)?.info)
    }

    @Test
    fun testMintInfoFromJson_LegacyVersion() {
        val jsonString = """
            {
                "name": "Legacy Mint",
                "version": "0.15.0"
            }
        """.trimIndent()
        
        val cachedInfo = CashuWalletManager.mintInfoFromJson(jsonString)
        assertNotNull(cachedInfo)
        assertEquals("Legacy Mint", cachedInfo?.name)
        assertNull(cachedInfo?.versionInfo)
    }

    @Test
    fun testMnemonicStorage() {
        setPrivateField("appContext", context)
        
        val mnemonic = "test mnemonic code"
        // Correct prefs name from PreferenceStore.WALLET_PREFS_NAME ("cashu_wallet_prefs")
        val prefs = context.getSharedPreferences("cashu_wallet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("wallet_mnemonic", mnemonic).apply()
        
        assertEquals(mnemonic, CashuWalletManager.getMnemonic())
    }
}
