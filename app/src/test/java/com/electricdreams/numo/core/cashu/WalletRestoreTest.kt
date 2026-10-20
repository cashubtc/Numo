package com.electricdreams.numo.core.cashu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ui.seed.CdkMnemonicRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.Wallet
import org.cashudevkit.WalletRepository
import org.cashudevkit.WalletStore
import org.cashudevkit.Amount
import org.cashudevkit.Restored
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
    @get:Rule
    val mnemonicValidation = CdkMnemonicRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalFactory = CashuWalletManager.repositoryFactory
    private lateinit var oldWallet: WalletRepository
    private lateinit var oldFile: File
    private lateinit var backup: File
    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")

    @Before
    fun setUp() {
        mnemonicValidation.accept(mnemonic)
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
        MintManager.getInstance(context).setMintChangeListener(null)
        setField("wallet", null)
        setField("database", null)
        setField("appContext", null)
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
        expectFailure {
            CashuWalletManager.restoreFromMnemonic(
                mnemonic,
                selectedMintUrls = MintManager.getInstance(context).getAllowedMints().take(1),
            ) { _, _, _, _ -> }
        }
        assertOriginalWallet()
        verify(replacement).close()
    }

    @Test
    fun `deselected unavailable mint does not prevent selected mint restore`() = runTest {
        val mints = MintManager.getInstance(context).getAllowedMints()
        val selectedUrl = mints.first()
        val unavailableUrl = mints.last()
        assertTrue(selectedUrl != unavailableUrl)
        val replacement: WalletRepository = mock()
        val mint: Wallet = mock()
        whenever(replacement.getWallet(any(), any())).thenReturn(mint)
        whenever(replacement.createWallet(eq(MintUrl(unavailableUrl)), any(), any()))
            .thenThrow(IllegalStateException("mint unavailable"))
        whenever(mint.restore()).thenReturn(Restored(Amount(0u), Amount(42u), Amount(0u)))
        CashuWalletManager.repositoryFactory = { _, _ -> replacement }
        val progressMints = mutableSetOf<String>()

        val result = CashuWalletManager.restoreFromMnemonic(
            mnemonic,
            selectedMintUrls = listOf(selectedUrl),
        ) { mintUrl, _, _, _ -> progressMints.add(mintUrl) }

        assertEquals(mapOf(selectedUrl to (0L to 42L)), result)
        assertEquals(setOf(selectedUrl), progressMints)
        assertSame(replacement, CashuWalletManager.getWallet())
        verify(replacement).createWallet(MintUrl(selectedUrl), CurrencyUnit.Sat, 10u)
        verify(replacement, never()).createWallet(eq(MintUrl(unavailableUrl)), any(), any())
        verify(replacement, never()).getWallet(eq(MintUrl(unavailableUrl)), any())
    }

    @Test
    fun `empty mint selection preserves active wallet`() = runTest {
        var constructed = false
        CashuWalletManager.repositoryFactory = { _, _ -> constructed = true; mock() }

        expectFailure {
            CashuWalletManager.restoreFromMnemonic(
                mnemonic,
                selectedMintUrls = emptyList(),
            ) { _, _, _, _ -> }
        }

        assertFalse(constructed)
        assertOriginalWallet()
    }

    @Test
    fun `initialization creates wallet after failed first restore with unchanged mints`() = runTest {
        setField("appContext", null)
        setField("wallet", null)
        PreferenceStore.wallet(context).remove("wallet_mnemonic")
        val mints = MintManager.getInstance(context).getAllowedMints()
        val stagedWallet: WalletRepository = mock()
        val mint: Wallet = mock()
        whenever(stagedWallet.getWallet(any(), any())).thenReturn(mint)
        whenever(mint.restore()).thenThrow(IllegalStateException("mint unavailable"))
        CashuWalletManager.repositoryFactory = { _, _ -> stagedWallet }

        expectFailure {
            CashuWalletManager.restoreFromMnemonic(mnemonic, context) { _, _, _, _ -> }
        }
        assertNull(CashuWalletManager.getWallet())
        assertNull(CashuWalletManager.getMnemonic())
        verify(stagedWallet).close()

        val newMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        CashuWalletManager.wipeDatabase(context)
        PreferenceStore.wallet(context).putString("wallet_mnemonic", newMnemonic)
        val newWallet: WalletRepository = mock()
        whenever(newWallet.getWallet(any(), any())).thenReturn(mock())
        CashuWalletManager.repositoryFactory = { seed, _ ->
            assertEquals(newMnemonic, seed)
            newWallet
        }

        CashuWalletManager.init(context)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                CashuWalletManager.walletState.first {
                    it == WalletState.READY && CashuWalletManager.getWallet() === newWallet
                }
            }
        }

        assertSame(newWallet, CashuWalletManager.getWallet())
        assertEquals(newMnemonic, CashuWalletManager.getMnemonic())
        assertEquals(mints, MintManager.getInstance(context).getAllowedMints())
        for (url in mints) {
            verify(newWallet).createWallet(MintUrl(url), CurrencyUnit.Sat, 10u)
        }
        verify(newWallet, never()).close()
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
