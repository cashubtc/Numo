package com.electricdreams.numo.core.cashu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.cashu.CashuWalletManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun testMintLimitsParsing_Nut04AndNut05() {
        val jsonString = """
            {
                "name": "Test Mint",
                "nuts": {
                    "4": {
                        "disabled": false,
                        "methods": [
                            { "method": "bolt11", "unit": "sat", "min_amount": 100, "max_amount": 10000 },
                            { "method": "bolt11", "unit": "usd", "min_amount": 1, "max_amount": 500 }
                        ]
                    },
                    "5": {
                        "disabled": false,
                        "methods": [
                            { "method": "bolt11", "unit": "sat", "min_amount": 100, "max_amount": 5000 }
                        ]
                    }
                }
            }
        """.trimIndent()

        val cachedInfo = CashuWalletManager.mintInfoFromJson(jsonString)
        assertNotNull(cachedInfo)
        assertNotNull(cachedInfo?.mintLimits)
        
        val mintLimits = cachedInfo?.mintLimits
        assertEquals(2, mintLimits?.mintMethods?.size)
        assertEquals(1, mintLimits?.meltMethods?.size)
        
        val bolt11Mint = mintLimits?.mintMethods?.find { it.method == "bolt11" && it.unit == "sat" }
        assertEquals(100L, bolt11Mint?.minAmount)
        assertEquals(10000L, bolt11Mint?.maxAmount)
        assertFalse(bolt11Mint?.disabled ?: true)
        
        val bolt11Melt = mintLimits?.meltMethods?.find { it.method == "bolt11" && it.unit == "sat" }
        assertEquals(100L, bolt11Melt?.minAmount)
        assertEquals(5000L, bolt11Melt?.maxAmount)
    }

    @Test
    fun testMintLimitsParsing_DisabledMint() {
        val jsonString = """
            {
                "name": "Disabled Mint",
                "nuts": {
                    "4": {
                        "disabled": true,
                        "methods": [
                            { "method": "bolt11", "unit": "sat", "min_amount": 100, "max_amount": 10000 }
                        ]
                    }
                }
            }
        """.trimIndent()

        val cachedInfo = CashuWalletManager.mintInfoFromJson(jsonString)
        assertNotNull(cachedInfo)
        assertNotNull(cachedInfo?.mintLimits)
        
        val bolt11Method = cachedInfo?.mintLimits?.mintMethods?.find { it.method == "bolt11" }
        assertTrue(bolt11Method?.disabled ?: false)
    }

    @Test
    fun testMintLimitsParsing_NullLimits() {
        val jsonString = """
            {
                "name": "No Limits Mint",
                "nuts": {
                    "4": {
                        "disabled": false,
                        "methods": [
                            { "method": "bolt11", "unit": "sat" }
                        ]
                    }
                }
            }
        """.trimIndent()

        val cachedInfo = CashuWalletManager.mintInfoFromJson(jsonString)
        assertNotNull(cachedInfo)
        assertNotNull(cachedInfo?.mintLimits)
        
        val bolt11Method = cachedInfo?.mintLimits?.mintMethods?.find { it.method == "bolt11" }
        assertNull(bolt11Method?.minAmount)
        assertNull(bolt11Method?.maxAmount)
    }

    @Test
    fun testMintInfoWithoutNuts_NoLimits() {
        val jsonString = """
            {
                "name": "Old Mint"
            }
        """.trimIndent()

        val cachedInfo = CashuWalletManager.mintInfoFromJson(jsonString)
        assertNotNull(cachedInfo)
        assertNull(cachedInfo?.mintLimits)
    }
}
