package com.electricdreams.numo.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class DeviceRecoveryBackupTest {

    private lateinit var context: Context
    private lateinit var mintManager: MintManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetPreferenceStore()
        resetMintManagerSingleton()
        mintManager = MintManager.getInstance(context)
        mintManager.resetToDefaults()

        // Register custom AndroidKeyStore provider for unit tests
        if (java.security.Security.getProvider("AndroidKeyStore") == null) {
            java.security.Security.addProvider(TestAndroidKeyStoreProvider())
        }
        TestAndroidKeyStoreProvider.keysByAlias.clear()

        // Set up mnemonic for CashuWalletManager
        val prefs = context.getSharedPreferences("cashu_wallet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("wallet_mnemonic", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about").apply()
        
        // Also ensure CashuWalletManager has the correct context
        setCashuWalletManagerPrivateField("appContext", context)
        
        // Disable backup before each test to start clean
        DeviceRecoveryBackup.disable(context)
    }

    @After
    fun tearDown() {
        setCashuWalletManagerPrivateField("appContext", null)
        context.getSharedPreferences("numo_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("cashu_wallet_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        resetPreferenceStore()
    }

    private fun resetMintManagerSingleton() {
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

    private fun setCashuWalletManagerPrivateField(fieldName: String, value: Any?) {
        try {
            val instance = CashuWalletManager
            val field = instance::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(instance, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resetPreferenceStore() {
        try {
            val field = com.electricdreams.numo.core.prefs.PreferenceStore::class.java.getDeclaredField("stores")
            field.isAccessible = true
            val storesMap = field.get(null) as? java.util.Map<*, *>
            storesMap?.clear()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Test
    fun `test enable, isEnabled, and decrypt`() {
        assertFalse(DeviceRecoveryBackup.isEnabled(context))
        assertFalse(DeviceRecoveryBackup.hasRestoredBackup(context))

        val password = "super_secure_recovery_password_123".toCharArray()
        DeviceRecoveryBackup.enable(context, password)

        assertTrue(DeviceRecoveryBackup.isEnabled(context))
        assertTrue(DeviceRecoveryBackup.hasRestoredBackup(context))

        val decryptPassword = "super_secure_recovery_password_123".toCharArray()
        val data = DeviceRecoveryBackup.decrypt(context, decryptPassword)

        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", data.mnemonic)
        assertEquals(mintManager.getAllowedMints(), data.mints)
        assertEquals(mintManager.getPreferredLightningMint(), data.preferredLightningMint)
        assertEquals(mintManager.getPreferredUnit(), data.preferredUnit)
    }

    @Test(expected = Exception::class)
    fun `test decrypt with incorrect password throws exception`() {
        val password = "super_secure_recovery_password_123".toCharArray()
        DeviceRecoveryBackup.enable(context, password)

        val wrongPassword = "wrong_password_123".toCharArray()
        DeviceRecoveryBackup.decrypt(context, wrongPassword)
    }

    @Test
    fun `test disable clears backup and files`() {
        val password = "super_secure_recovery_password_123".toCharArray()
        DeviceRecoveryBackup.enable(context, password)

        assertTrue(DeviceRecoveryBackup.isEnabled(context))
        assertTrue(DeviceRecoveryBackup.hasRestoredBackup(context))

        DeviceRecoveryBackup.disable(context)

        assertFalse(DeviceRecoveryBackup.isEnabled(context))
        assertFalse(DeviceRecoveryBackup.hasRestoredBackup(context))
    }

    @Test
    fun `test updateIfEnabled updates mint list`() {
        val password = "super_secure_recovery_password_123".toCharArray()
        DeviceRecoveryBackup.enable(context, password)

        // Add a new mint
        val newMint = "https://custom.mint.url"
        mintManager.addMint(newMint)

        // Update backup
        DeviceRecoveryBackup.updateIfEnabled(context)

        val decryptPassword = "super_secure_recovery_password_123".toCharArray()
        val data = DeviceRecoveryBackup.decrypt(context, decryptPassword)

        assertTrue(data.mints.contains(newMint))
    }
}

class TestAndroidKeyStoreProvider : java.security.Provider("AndroidKeyStore", 1.0, "Test provider") {
    companion object {
        val keysByAlias = mutableMapOf<String, java.security.Key>()
    }
    init {
        put("KeyStore.AndroidKeyStore", TestKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", TestKeyGeneratorSpi::class.java.name)
    }
}

class TestKeyStoreSpi : java.security.KeyStoreSpi() {
    override fun engineGetKey(alias: String?, password: CharArray?): java.security.Key? {
        return TestAndroidKeyStoreProvider.keysByAlias[alias]
    }

    override fun engineGetCertificateChain(alias: String?): Array<java.security.cert.Certificate>? {
        return null
    }

    override fun engineGetCertificate(alias: String?): java.security.cert.Certificate? {
        return null
    }

    override fun engineGetCreationDate(alias: String?): java.util.Date? {
        return java.util.Date()
    }

    override fun engineSetKeyEntry(alias: String?, key: java.security.Key?, password: CharArray?, chain: Array<out java.security.cert.Certificate>?) {
        if (alias != null && key != null) {
            TestAndroidKeyStoreProvider.keysByAlias[alias] = key
        }
    }

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out java.security.cert.Certificate>?) {
    }

    override fun engineSetCertificateEntry(alias: String?, cert: java.security.cert.Certificate?) {
    }

    override fun engineDeleteEntry(alias: String?) {
        if (alias != null) {
            TestAndroidKeyStoreProvider.keysByAlias.remove(alias)
        }
    }

    override fun engineAliases(): java.util.Enumeration<String> {
        return java.util.Collections.enumeration(TestAndroidKeyStoreProvider.keysByAlias.keys)
    }

    override fun engineContainsAlias(alias: String?): Boolean {
        return TestAndroidKeyStoreProvider.keysByAlias.containsKey(alias)
    }

    override fun engineSize(): Int {
        return TestAndroidKeyStoreProvider.keysByAlias.size
    }

    override fun engineIsKeyEntry(alias: String?): Boolean {
        return TestAndroidKeyStoreProvider.keysByAlias.containsKey(alias)
    }

    override fun engineIsCertificateEntry(alias: String?): Boolean {
        return false
    }

    override fun engineGetCertificateAlias(cert: java.security.cert.Certificate?): String? {
        return null
    }

    override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) {
    }

    override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {
    }
}

class TestKeyGeneratorSpi : javax.crypto.KeyGeneratorSpi() {
    private val delegate = javax.crypto.KeyGenerator.getInstance("AES")
    private var alias: String? = null

    override fun engineInit(keysize: Int, random: java.security.SecureRandom?) {
        delegate.init(keysize, random)
    }

    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: java.security.SecureRandom?) {
        if (params is android.security.keystore.KeyGenParameterSpec) {
            alias = params.keystoreAlias
        }
        delegate.init(256, random)
    }

    override fun engineInit(random: java.security.SecureRandom?) {
        delegate.init(random)
    }

    override fun engineGenerateKey(): javax.crypto.SecretKey {
        val key = delegate.generateKey()
        alias?.let { TestAndroidKeyStoreProvider.keysByAlias[it] = key }
        return key
    }
}