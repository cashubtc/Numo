package com.electricdreams.numo.feature.autowithdraw

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.FinalizedMelt
import org.cashudevkit.MeltQuote
import org.cashudevkit.MintQuote
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod
import org.cashudevkit.PreparedMelt
import org.cashudevkit.QuoteState
import org.cashudevkit.Wallet
import org.cashudevkit.WalletKey
import org.cashudevkit.WalletRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutoWithdrawUnitTest {
    private lateinit var context: Context
    private lateinit var mints: MintManager
    private lateinit var settings: AutoWithdrawSettingsManager
    private lateinit var repository: WalletRepository
    private lateinit var wallet: Wallet
    private lateinit var prepared: PreparedMelt
    private val mintUrl = "https://mint.example"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        mints = MintManager.getInstance(context)
        mints.setMintChangeListener(null)
        mints.setPreferredUnit("sat")
        settings = AutoWithdrawSettingsManager.getInstance(context)
        settings.setGloballyEnabled(true)
        settings.saveMintSettings(MintWithdrawSettings(
            mintUrl = mintUrl, enabled = true, thresholdSats = 50_000,
            withdrawPercentage = 95, lightningAddress = "merchant@example.com",
        ))
        repository = mock()
        wallet = mock()
        prepared = mock()
        CashuWalletManager.appContext = context
        setRepository(repository)
    }

    @After
    fun tearDown() {
        setRepository(null)
        Dispatchers.resetMain()
    }

    private fun setRepository(value: WalletRepository?) {
        CashuWalletManager::class.java.getDeclaredField("wallet").apply {
            isAccessible = true
            set(null, value)
        }
    }

    private fun manager(scope: CoroutineScope? = null): AutoWithdrawManager {
        val decode: (String) -> ULong? = { request ->
            assertEquals("valuation-invoice", request)
            100_000_000uL
        }
        return if (scope == null) AutoWithdrawManager(context, decode)
        else AutoWithdrawManager(context, decode, scope)
    }

    private suspend fun stubWallet(
        unit: CurrencyUnit,
        balanceAtomic: ULong,
        quoteAtomic: ULong,
        feeAtomic: ULong,
    ) {
        whenever(repository.getBalances()).thenReturn(mapOf(
            WalletKey(MintUrl(mintUrl), unit) to Amount(balanceAtomic),
            WalletKey(MintUrl("https://other.example"), CurrencyUnit.Sat) to Amount(900_000uL),
        ))
        whenever(repository.getWallet(any(), eq(unit))).thenReturn(wallet)
        val valuation = mock<MintQuote>()
        whenever(valuation.unit).thenReturn(unit)
        whenever(valuation.amount).thenReturn(Amount(balanceAtomic))
        whenever(valuation.expiry).thenReturn(ULong.MAX_VALUE)
        whenever(valuation.request).thenReturn("valuation-invoice")
        whenever(wallet.mintQuote(PaymentMethod.Bolt11, Amount(balanceAtomic), null, null))
            .thenReturn(valuation)
        val melt = mock<MeltQuote>()
        whenever(melt.id).thenReturn("melt-quote")
        whenever(melt.unit).thenReturn(unit)
        whenever(melt.amount).thenReturn(Amount(quoteAtomic))
        whenever(melt.feeReserve).thenReturn(Amount(feeAtomic))
        whenever(wallet.meltLightningAddressQuote(any(), any())).thenReturn(melt)
        whenever(wallet.prepareMelt("melt-quote")).thenReturn(prepared)
        whenever(prepared.confirm()).thenReturn(FinalizedMelt(
            quoteId = "melt-quote", state = QuoteState.PAID, preimage = "preimage",
            change = emptyList(), amount = Amount(quoteAtomic), feePaid = Amount(feeAtomic),
        ))
    }

    @Test
    fun `payment callback pins usd despite preference changes and accounts in both units`() = runTest {
        stubWallet(CurrencyUnit.Usd, 10_000uL, 9_500uL, 1uL)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = manager(scope)
        try {
            manager.onPaymentReceived("", "$mintUrl/", "USD")
            mints.setPreferredUnit("points")
            scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
            verify(repository).getWallet(MintUrl(mintUrl), CurrencyUnit.Usd)
            verify(repository, never()).getWallet(any(), eq(CurrencyUnit.Sat))
            verify(wallet).meltLightningAddressQuote("merchant@example.com", Amount(95_000_000uL))
            verify(prepared).confirm()
            val entry = manager.getHistory().single()
            assertEquals(WithdrawHistoryEntry.STATUS_COMPLETED, entry.status)
            assertEquals(95_000L, entry.amountSats)
            assertEquals(10L, entry.feeSats)
            assertEquals("usd", entry.sourceUnit)
            assertEquals(9_500L, entry.sourceAmountAtomic)
            assertEquals(1L, entry.sourceFeeAtomic)
            assertFalse(manager.isWithdrawing())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `msat withdrawal converts the threshold and invoice without a thousandfold overpayment`() = runTest {
        stubWallet(CurrencyUnit.Msat, 100_000_000uL, 95_000_000uL, 1_000uL)
        val manager = manager()
        manager.checkAndTriggerWithdrawals(mintUrl, "msat")
        verify(wallet).meltLightningAddressQuote("merchant@example.com", Amount(95_000_000uL))
        verify(prepared).confirm()
        assertEquals(95_000L, manager.getHistory().single().amountSats)
        assertEquals(1L, manager.getHistory().single().feeSats)
    }

    @Test
    fun `melt conversion cannot exceed the configured native balance percentage`() = runTest {
        stubWallet(CurrencyUnit.Usd, 10_000uL, 9_600uL, 1uL)
        val manager = manager()
        manager.checkAndTriggerWithdrawals(mintUrl, "usd")
        verify(wallet, never()).prepareMelt(any())
        assertEquals(WithdrawHistoryEntry.STATUS_FAILED, manager.getHistory().single().status)
    }

    @Test
    fun `missing conversion quote does not spend another unit`() = runTest {
        stubWallet(CurrencyUnit.Usd, 10_000uL, 9_500uL, 1uL)
        whenever(wallet.mintQuote(PaymentMethod.Bolt11, Amount(10_000uL), null, null))
            .thenThrow(IllegalStateException("No quote available"))
        val manager = manager()
        manager.checkAndTriggerWithdrawals(mintUrl, "usd")
        verify(wallet, never()).prepareMelt(any())
        verify(repository, never()).getWallet(any(), eq(CurrencyUnit.Sat))
        assertEquals(0, manager.getHistory().size)
    }

    @Test
    fun `sat withdrawal retains its denomination when usd is preferred`() = runTest {
        stubWallet(CurrencyUnit.Sat, 100_000uL, 95_000uL, 10uL)
        mints.setPreferredUnit("usd")
        val manager = manager()
        manager.checkAndTriggerWithdrawals(mintUrl, "sat")
        verify(wallet, never()).mintQuote(any(), any(), any(), any())
        verify(wallet).meltLightningAddressQuote("merchant@example.com", Amount(95_000_000uL))
        verify(prepared).confirm()
        val entry = manager.getHistory().single()
        assertEquals(95_000L, entry.amountSats)
        assertEquals(10L, entry.feeSats)
        assertEquals("sat", entry.sourceUnit)
    }

    @Test
    fun `overlapping payment checks cannot withdraw the same valued balance twice`() = runTest {
        stubWallet(CurrencyUnit.Usd, 10_000uL, 9_500uL, 1uL)
        val valuationStarted = CompletableDeferred<Unit>()
        val resumeValuation = CountDownLatch(1)
        val manager = AutoWithdrawManager(context, {
            valuationStarted.complete(Unit)
            check(resumeValuation.await(5, TimeUnit.SECONDS))
            100_000_000uL
        })
        val firstCheck = launch { manager.checkAndTriggerWithdrawals(mintUrl, "usd") }
        try {
            valuationStarted.await()
            manager.checkAndTriggerWithdrawals(mintUrl, "usd")
            verify(repository).getBalances()
        } finally {
            resumeValuation.countDown()
            firstCheck.join()
        }
        verify(prepared).confirm()
        assertEquals(1, manager.getHistory().size)
        assertFalse(manager.isWithdrawing())
    }

    @Test
    fun `percentage calculation is checked for large unit balances`() {
        assertEquals(8_762_203_435_012_037_016L, settings.calculateWithdrawAmount(mintUrl, Long.MAX_VALUE))
    }
}
