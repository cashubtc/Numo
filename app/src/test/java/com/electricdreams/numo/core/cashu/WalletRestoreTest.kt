package com.electricdreams.numo.core.cashu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.cashudevkit.Wallet
import org.cashudevkit.WalletRepository
import org.cashudevkit.WalletStore
import org.cashudevkit.Amount
import org.cashudevkit.Restored
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalFactory = CashuWalletManager.repositoryFactory
    private lateinit var oldWallet: WalletRepository
    private lateinit var oldFile: File
    private lateinit var backup: File
    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")

    @Before
    fun setUp() {
        PreferenceStore.wallet(context).remove("wallet_database_file")
        PreferenceStore.wallet(context).putString("wallet_mnemonic", "original seed")
        CashuWalletManager.appContext = context
        oldWallet = mock()
        setField("wallet", oldWallet)
        setField("database", null)
        oldFile = context.getDatabasePath("cashu_wallet.db")
        oldFile.parentFile?.mkdirs()
        oldFile.writeText("original database")
        backup = File(context.filesDir, "numo_recovery_backup.json")
        backup.writeText("original encrypted backup")
        MintManager.getInstance(context).setMintChangeListener(null)
    }

    @After
    fun tearDown() {
        CashuWalletManager.repositoryFactory = originalFactory
        setField("wallet", null)
        setField("database", null)
    }

    @Test
    fun `invalid phrase preserves wallet database mnemonic and backup`() = runTest {
        var constructed = false
        CashuWalletManager.repositoryFactory = { _, _ -> constructed = true; mock() }
        expectFailure { CashuWalletManager.restoreFromMnemonic(List(12) { "abandon" }.joinToString(" ")) { _, _, _, _ -> } }
        assertFalse(constructed)
        assertOriginalWallet()
    }

    @Test
    fun `construction failure preserves active wallet`() = runTest {
        CashuWalletManager.repositoryFactory = { _, store ->
            File((store as WalletStore.Sqlite).path).writeText("staged database")
            throw IllegalStateException("construction failed")
        }
        expectFailure { CashuWalletManager.restoreFromMnemonic(mnemonic) { _, _, _, _ -> } }
        assertOriginalWallet()
        assertTrue(context.databaseList().none { it.startsWith("cashu_wallet_restore_") })
    }

    @Test
    fun `mint restore failure preserves active wallet`() = runTest {
        val replacement: WalletRepository = mock()
        val mint: Wallet = mock()
        whenever(replacement.getWallet(any(), any())).thenReturn(mint)
        whenever(mint.restore()).thenThrow(IllegalStateException("mint unavailable"))
        CashuWalletManager.repositoryFactory = { _, _ -> replacement }
        expectFailure { CashuWalletManager.restoreFromMnemonic(mnemonic) { _, _, _, _ -> } }
        assertOriginalWallet()
        verify(replacement).close()
    }

    @Test
    fun `cancelled restore preserves active wallet`() = runTest {
        val replacement: WalletRepository = mock()
        CashuWalletManager.repositoryFactory = { _, _ -> replacement }
        expectFailure {
            CashuWalletManager.restoreFromMnemonic(mnemonic) { _, _, _, _ ->
                throw CancellationException("cancelled")
            }
        }
        assertOriginalWallet()
        verify(replacement).close()
    }

    @Test
    fun `successful restore publishes matching seed and staged database together`() = runTest {
        val replacement: WalletRepository = mock()
        val mint: Wallet = mock()
        whenever(replacement.getWallet(any(), any())).thenReturn(mint)
        whenever(mint.restore()).thenReturn(Restored(Amount(0u), Amount(42u), Amount(0u)))
        var stagedPath: String? = null
        CashuWalletManager.repositoryFactory = { seed, store ->
            assertEquals(mnemonic, seed)
            stagedPath = (store as WalletStore.Sqlite).path
            File(store.path).writeText("restored database")
            replacement
        }
        val result = CashuWalletManager.restoreFromMnemonic(mnemonic) { _, _, _, _ ->
            assertOriginalWallet()
        }
        assertSame(replacement, CashuWalletManager.getWallet())
        assertEquals(mnemonic, CashuWalletManager.getMnemonic())
        val activeName = PreferenceStore.wallet(context).getString("wallet_database_file")
        assertEquals(stagedPath, context.getDatabasePath(activeName).absolutePath)
        assertEquals("restored database", File(requireNotNull(stagedPath)).readText())
        assertEquals("original database", oldFile.readText())
        assertEquals("original seed", PreferenceStore.wallet(context)
            .getString("wallet_archive_cashu_wallet.db"))
        assertTrue(result.values.all { it.second == 42L })
        assertEquals(WalletState.READY, CashuWalletManager.walletState.value)
        verify(oldWallet).close()
        verify(replacement, never()).close()
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Restore must fail", failed)
    }

    private fun assertOriginalWallet() {
        assertSame(oldWallet, CashuWalletManager.getWallet())
        assertEquals("original seed", CashuWalletManager.getMnemonic())
        assertEquals("original database", oldFile.readText())
        assertEquals("original encrypted backup", backup.readText())
        verify(oldWallet, never()).close()
    }

    private fun setField(name: String, value: Any?) {
        CashuWalletManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(CashuWalletManager, value)
        }
    }
}
