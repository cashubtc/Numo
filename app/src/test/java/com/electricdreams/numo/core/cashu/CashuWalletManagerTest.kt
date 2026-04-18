package com.electricdreams.numo.core.cashu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    fun testGetMnemonic_whenNotInitialized_returnsNull() {
        // appContext is not initialized → getMnemonic must return null, not throw
        assertNull(CashuWalletManager.getMnemonic())
    }

    @Test
    fun testGetWallet_whenNotInitialized_returnsNull() {
        assertNull(CashuWalletManager.getWallet())
    }

    @Test
    fun testMintInfoFromJson_emptyJson_returnsNullName() {
        val cachedInfo = CashuWalletManager.mintInfoFromJson("{}")
        assertNotNull("empty JSON object should still return a CachedMintInfo", cachedInfo)
        assertNull("name should be null for empty JSON", cachedInfo?.name)
        assertNull("versionInfo should be null for empty JSON", cachedInfo?.versionInfo)
        assertEquals("contact list should be empty for empty JSON", 0, cachedInfo?.contact?.size)
    }

    @Test
    fun testMintInfoFromJson_invalidJson_returnsNull() {
        assertNull("invalid JSON should return null", CashuWalletManager.mintInfoFromJson("not-json"))
        assertNull("bare string should return null", CashuWalletManager.mintInfoFromJson("null"))
        assertNull("empty string should return null", CashuWalletManager.mintInfoFromJson(""))
    }

    @Test
    fun testMintInfoFromJson_nullFields_handledGracefully() {
        val json = """{"name": null, "description": null, "motd": null}"""
        val cachedInfo = CashuWalletManager.mintInfoFromJson(json)
        assertNotNull(cachedInfo)
        assertNull("explicit JSON null name should be null in result", cachedInfo?.name)
        assertNull("explicit JSON null description should be null", cachedInfo?.description)
        assertNull("explicit JSON null motd should be null", cachedInfo?.motd)
    }

    @Test
    fun testMintInfoFromJson_multipleContacts() {
        val json = """
            {
                "name": "Multi-Contact Mint",
                "contact": [
                    {"method": "email", "info": "a@example.com"},
                    {"method": "twitter", "info": "@mintoperator"},
                    {"method": "nostr", "info": "npub1xxx"}
                ]
            }
        """.trimIndent()
        val cachedInfo = CashuWalletManager.mintInfoFromJson(json)
        assertNotNull(cachedInfo)
        assertEquals("Should have 3 contacts", 3, cachedInfo?.contact?.size)
        assertEquals("email", cachedInfo?.contact?.get(0)?.method)
        assertEquals("a@example.com", cachedInfo?.contact?.get(0)?.info)
        assertEquals("twitter", cachedInfo?.contact?.get(1)?.method)
        assertEquals("nostr", cachedInfo?.contact?.get(2)?.method)
    }

    @Test
    fun testMintInfoFromJson_contactMissingField_skipped() {
        // Contact without "info" field should be skipped (both fields required)
        val json = """
            {
                "name": "Test",
                "contact": [
                    {"method": "email"},
                    {"method": "twitter", "info": "@ok"}
                ]
            }
        """.trimIndent()
        val cachedInfo = CashuWalletManager.mintInfoFromJson(json)
        assertNotNull(cachedInfo)
        // Only the complete contact should be present
        assertEquals("Incomplete contact should be skipped", 1, cachedInfo?.contact?.size)
        assertEquals("twitter", cachedInfo?.contact?.get(0)?.method)
    }

    @Test
    fun testMintInfoFromJson_versionAsObject_parsed() {
        val json = """
            {
                "name": "CDK Mint",
                "version": {"name": "cdk-mintd", "version": "0.16.0"}
            }
        """.trimIndent()
        val cachedInfo = CashuWalletManager.mintInfoFromJson(json)
        assertNotNull(cachedInfo)
        assertNotNull(cachedInfo?.versionInfo)
        assertEquals("cdk-mintd", cachedInfo?.versionInfo?.name)
        assertEquals("0.16.0", cachedInfo?.versionInfo?.version)
    }

    @Test
    fun testMintInfoFromJson_allOptionalFieldsPresent() {
        val json = """
            {
                "name": "Full Mint",
                "description": "Short desc",
                "descriptionLong": "A longer description with more detail",
                "motd": "Message of the day",
                "iconUrl": "https://example.com/icon.svg",
                "pubkey": "02abc",
                "version": {"name": "nutshell", "version": "0.15.3"},
                "contact": [{"method": "email", "info": "ops@example.com"}]
            }
        """.trimIndent()
        val cachedInfo = CashuWalletManager.mintInfoFromJson(json)
        assertNotNull(cachedInfo)
        assertEquals("Full Mint", cachedInfo?.name)
        assertEquals("Short desc", cachedInfo?.description)
        assertEquals("A longer description with more detail", cachedInfo?.descriptionLong)
        assertEquals("Message of the day", cachedInfo?.motd)
        assertEquals("https://example.com/icon.svg", cachedInfo?.iconUrl)
        assertNotNull(cachedInfo?.versionInfo)
        assertEquals(1, cachedInfo?.contact?.size)
    }

    @Test
    fun testCachedMintInfo_dataClass_equalsAndCopy() {
        val info1 = CashuWalletManager.CachedMintInfo(
            name = "Test",
            description = null,
            descriptionLong = null,
            versionInfo = null,
            motd = null,
            iconUrl = null,
            contact = emptyList()
        )
        val info2 = info1.copy(name = "Test2")
        assertEquals("Test", info1.name)
        assertEquals("Test2", info2.name)
        // Other fields should be equal
        assertEquals(info1.contact, info2.contact)
    }

    @Test
    fun testCachedVersionInfo_dataClass() {
        val v = CashuWalletManager.CachedVersionInfo(name = "mintd", version = "1.0.0")
        assertEquals("mintd", v.name)
        assertEquals("1.0.0", v.version)
        assertEquals(v, v.copy())
    }

    @Test
    fun testCachedContactInfo_dataClass() {
        val c = CashuWalletManager.CachedContactInfo(method = "email", info = "a@b.com")
        assertEquals("email", c.method)
        assertEquals("a@b.com", c.info)
    }
}
