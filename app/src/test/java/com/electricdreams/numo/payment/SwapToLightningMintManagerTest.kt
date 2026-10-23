package com.electricdreams.numo.payment

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cashudevkit.Amount
import org.cashudevkit.FinalizedMelt
import org.cashudevkit.MeltQuote
import org.cashudevkit.MintQuote
import org.cashudevkit.PreparedMelt
import org.cashudevkit.Proof
import org.cashudevkit.QuoteState
import org.cashudevkit.Wallet
import org.cashudevkit.WalletRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SwapToLightningMintManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: Wallet = mock()
    private val destination: Wallet = mock()
    private val prepared: PreparedMelt = mock()
    private val checked: MintQuote = mock()
    private val payerProof: Proof = mock()
    private val factory = SwapToLightningMintManager.temporaryWalletFactory
    private var openedId: String? = null
    private lateinit var paymentId: String

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        AppGlobals.init(context)
        CashuWalletManager.appContext = context
        val repository: WalletRepository = mock()
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", repository)
        val mints: MintManager = mock()
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", mints)
        whenever(mints.getPreferredUnit()).thenReturn("sat")
        whenever(mints.getPreferredLightningMint("sat")).thenReturn("https://merchant.example")
        whenever(mints.getMintLimits(any(), any(), any(), any())).thenReturn(
            CashuWalletManager.MintLimits(listOf(CashuWalletManager.MintMethodSettings(
                "bolt11", "sat", null, null, false)), emptyList()))
        whenever(repository.getWallet(any(), any())).thenReturn(destination)
        val quote: MintQuote = mock()
        whenever(quote.id).thenReturn("mint-quote")
        whenever(quote.request).thenReturn("invoice")
        whenever(destination.mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(quote)
        val melt: MeltQuote = mock()
        whenever(melt.id).thenReturn("melt-quote")
        whenever(melt.amount).thenReturn(Amount(985u))
        whenever(melt.feeReserve).thenReturn(Amount(10u))
        whenever(source.meltQuote(any(), any(), anyOrNull(), anyOrNull())).thenReturn(melt)
        whenever(source.prepareMeltProofs(any(), any())).thenReturn(prepared)
        whenever(payerProof.amount).thenReturn(Amount(1_000u))
        whenever(prepared.inputFeeWithoutSwap()).thenReturn(Amount(0u))
        whenever(prepared.confirmWithOptions(any())).thenReturn(
            FinalizedMelt("melt-quote", QuoteState.PAID, null, emptyList(), Amount(985u), Amount(10u)))
        whenever(checked.state).thenReturn(QuoteState.PENDING)
        whenever(destination.checkMintQuote("mint-quote")).thenReturn(checked)
        SwapToLightningMintManager.temporaryWalletFactory = { _, id, _, _ ->
            openedId = id
            source
        }
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit().clear().commit()
        paymentId = PaymentsHistoryActivity.addPendingPayment(context, 1_000L, "sat", 1_000L,
            null, null, null)
    }

    @After
    fun tearDown() {
        SwapToLightningMintManager.temporaryWalletFactory = factory
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", null)
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", null)
        Dispatchers.resetMain()
    }

    @Test
    fun `retrying a pending payment checks existing recovery data without another melt`() = runTest {
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        verify(prepared).confirmWithOptions(any())
    }

    @Test
    fun `paid melt and pending mint quote remain pending`() = runTest {
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertTrue(entry().isPending())
        verify(destination, never()).mint(any(), any(), anyOrNull())
    }

    @Test
    fun `paid melt and unpaid mint quote remain pending`() = runTest {
        whenever(checked.state).thenReturn(QuoteState.UNPAID)
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertTrue(entry().isPending())
    }

    @Test
    fun `quote check error after paid melt remains pending`() = runTest {
        whenever(destination.checkMintQuote("mint-quote")).thenAnswer { throw IOException("offline") }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertTrue(entry().isPending())
    }

    @Test
    fun `mint failure can be retried without melting again`() = runTest {
        whenever(checked.state).thenReturn(QuoteState.PAID)
        whenever(destination.mint(any(), any(), anyOrNull())).thenAnswer { throw IOException("offline") }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        whenever(destination.mint(any(), any(), anyOrNull())).thenReturn(listOf(mock<Proof>()))
        assertTrue(SwapToLightningMintManager.tryFinalizePendingSwap(context, entry()))
        assertTrue(entry().isCompleted())
        verify(prepared).confirmWithOptions(any())
    }

    @Test
    fun `transport without payment ID persists a resumable quote`() = runTest {
        val result = swap(null) as SwapToLightningMintManager.SwapResult.Pending
        assertEquals(openedId, result.paymentId)
        val saved = PaymentsHistoryActivity.getPaymentEntryById(context, result.paymentId)
        assertEquals("mint-quote", saved?.getSwapLightningQuoteId())
        assertTrue(saved?.isPending() == true)
    }

    @Test
    fun `ambiguous melt error remains pending with recovery data`() = runTest {
        whenever(prepared.confirmWithOptions(any())).thenAnswer { throw IOException("connection lost") }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertNotNull(entry().swapToLightningMintJson)
        assertTrue(entry().isPending())
        verify(source).close()
    }

    @Test
    fun `preparation failure leaves checkout retryable`() = runTest {
        whenever(source.prepareMeltProofs(any(), any())).thenAnswer {
            throw IOException("Could not prepare payment")
        }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Failure)
        assertNull(entry().swapToLightningMintJson)
        verify(prepared, never()).confirmWithOptions(any())

        whenever(source.prepareMeltProofs(any(), any())).thenReturn(prepared)
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        verify(prepared).confirmWithOptions(any())
    }

    @Test
    fun `input fees that exceed available balance cancel preparation and allow retry`() = runTest {
        whenever(prepared.inputFeeWithoutSwap()).thenReturn(Amount(6u))
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Failure)
        assertNull(entry().swapToLightningMintJson)
        verify(prepared).cancel()
        verify(prepared, never()).confirmWithOptions(any())

        whenever(prepared.inputFeeWithoutSwap()).thenReturn(Amount(0u))
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        verify(prepared).confirmWithOptions(any())
    }

    @Test
    fun `input fees use the presented proof balance`() = runTest {
        whenever(payerProof.amount).thenReturn(Amount(998u))
        whenever(prepared.inputFeeWithoutSwap()).thenReturn(Amount(4u))
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Failure)
        assertNull(entry().swapToLightningMintJson)
        verify(prepared).cancel()
        verify(prepared, never()).confirmWithOptions(any())
    }

    @Test
    fun `input fees that exactly fit the balance can be confirmed`() = runTest {
        whenever(prepared.inputFeeWithoutSwap()).thenReturn(Amount(5u))
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        verify(prepared).confirmWithOptions(any())
        verify(prepared, never()).cancel()
    }

    @Test
    fun `checkout completion during preparation cancels the unsubmitted melt`() = runTest {
        whenever(source.prepareMeltProofs(any(), any())).thenAnswer {
            PaymentsHistoryActivity.completePendingPayment(context, paymentId, "",
                PaymentHistoryEntry.TYPE_ARKOOR, "https://original.example")
            prepared
        }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Failure)
        assertTrue(entry().isCompleted())
        assertNull(entry().swapToLightningMintJson)
        verify(prepared).cancel()
        verify(prepared, never()).confirmWithOptions(any())
    }

    @Test
    fun `recovery metadata is saved before confirming the melt`() = runTest {
        whenever(prepared.confirmWithOptions(any())).thenAnswer {
            val saved = PaymentsHistoryActivity.getPaymentEntryById(context, requireNotNull(openedId))
            assertNotNull(saved?.swapToLightningMintJson)
            FinalizedMelt("melt-quote", QuoteState.PAID, null, emptyList(), Amount(985u), Amount(10u))
        }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
    }

    @Test
    fun `swap quote is claimed after another lightning quote completes checkout`() = runTest {
        assertSwapClaimedAfterCheckoutCompleted(PaymentHistoryEntry.TYPE_LIGHTNING)
    }

    @Test
    fun `swap quote is claimed after arkoor completes checkout`() = runTest {
        assertSwapClaimedAfterCheckoutCompleted(PaymentHistoryEntry.TYPE_ARKOOR)
    }

    @Test
    fun `completed checkout does not confirm an unpaid swap quote`() = runTest {
        swap()
        PaymentsHistoryActivity.completePendingPayment(context, paymentId, "",
            PaymentHistoryEntry.TYPE_ARKOOR, "https://original.example")
        assertFalse(SwapToLightningMintManager.tryFinalizePendingSwap(context, entry()))
        verify(destination, never()).mint(any(), any(), anyOrNull())
    }

    @Test
    fun `empty mint response remains pending`() = runTest {
        whenever(checked.state).thenReturn(QuoteState.PAID)
        whenever(destination.mint(any(), any(), anyOrNull())).thenReturn(emptyList())
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertTrue(entry().isPending())
    }

    @Test
    fun `uncertain source settlement reopens the same swap wallet for recovery`() = runTest {
        whenever(prepared.confirmWithOptions(any())).thenAnswer { throw IOException("connection lost") }
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Pending)
        assertFalse(SwapToLightningMintManager.tryFinalizePendingSwap(context, entry()))
        assertEquals(paymentId, openedId)
        verify(source).recoverIncompleteSagas()
        verify(prepared).confirmWithOptions(any())
    }

    @Test
    fun `previously expired swap can still be finalized`() = runTest {
        swap()
        val expired = entry().copy(rawStatus = PaymentHistoryEntry.STATUS_EXPIRED)
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit()
            .putString("history", com.google.gson.Gson().toJson(listOf(expired))).commit()
        whenever(checked.state).thenReturn(QuoteState.ISSUED)
        assertTrue(SwapToLightningMintManager.tryFinalizePendingSwap(context, expired))
        assertTrue(entry().isCompleted())
    }

    @Test
    fun `issued quote finalizes an existing pending payment`() = runTest {
        whenever(checked.state).thenReturn(QuoteState.ISSUED)
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Success)
        assertFalse(entry().isPending())
        assertTrue(entry().isCompleted())
    }

    @Test
    fun `checking a claimed quote does not mint it again`() = runTest {
        whenever(checked.state).thenReturn(QuoteState.PAID)
        whenever(destination.mint(any(), any(), anyOrNull())).thenReturn(listOf(mock<Proof>()))
        assertTrue(swap() is SwapToLightningMintManager.SwapResult.Success)

        whenever(checked.state).thenReturn(QuoteState.ISSUED)
        assertTrue(SwapToLightningMintManager.tryFinalizePendingSwap(context, entry()))
        verify(destination).mint("mint-quote", org.cashudevkit.SplitTarget.None, null)
    }

    private suspend fun assertSwapClaimedAfterCheckoutCompleted(paymentType: String) {
        swap()
        PaymentsHistoryActivity.completePendingPayment(context, paymentId, "", paymentType,
            "https://original.example", lightningQuoteId = "original-quote",
            lightningMintUrl = "https://original.example")
        whenever(checked.state).thenReturn(QuoteState.PAID)
        whenever(destination.mint(any(), any(), anyOrNull())).thenReturn(listOf(mock<Proof>()))

        assertTrue(SwapToLightningMintManager.tryFinalizePendingSwap(context, entry()))
        verify(destination).mint("mint-quote", org.cashudevkit.SplitTarget.None, null)
        assertEquals(paymentType, entry().paymentType)
        assertEquals("original-quote", entry().lightningQuoteId)
    }

    private suspend fun swap(id: String? = paymentId) = SwapToLightningMintManager.swapFromUnknownMint(
        context, listOf(payerProof), 1_000L, "https://payer.example",
        SwapToLightningMintManager.PaymentContext(id, 1_000L, "sat"))

    private fun entry() = requireNotNull(PaymentsHistoryActivity.getPaymentEntryById(context, paymentId))
}
