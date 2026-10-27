package com.electricdreams.numo.payment

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cashudevkit.MintQuote
import org.cashudevkit.QuoteState
import org.cashudevkit.WalletInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SwapToLightningMintManagerTest {
    private val wallet = mock<WalletInterface>()
    private val quoteId = "swap-lightning-quote"
    private lateinit var logMock: MockedStatic<Log>

    @Before
    fun setUp() {
        logMock = mockStatic(Log::class.java)
    }

    @After
    fun tearDown() {
        logMock.close()
    }

    @Test
    fun `paid quote returns immediately`() = runTest {
        val paid = quote(QuoteState.PAID)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(paid)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(paid, result)
        assertEquals(0L, testScheduler.currentTime)
        verify(wallet).checkMintQuote(quoteId)
    }

    @Test
    fun `issued quote returns immediately`() = runTest {
        val issued = quote(QuoteState.ISSUED)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(issued)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(issued, result)
        assertEquals(0L, testScheduler.currentTime)
        verify(wallet).checkMintQuote(quoteId)
    }

    @Test
    fun `unpaid and pending quotes retry until paid`() = runTest {
        val unpaid = quote(QuoteState.UNPAID)
        val pending = quote(QuoteState.PENDING)
        val paid = quote(QuoteState.PAID)
        whenever(wallet.checkMintQuote(quoteId))
            .thenReturn(unpaid, pending, paid)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(paid, result)
        assertEquals(2_000L, testScheduler.currentTime)
        verify(wallet, times(3)).checkMintQuote(quoteId)
    }

    @Test
    fun `pending quote can become issued by another component`() = runTest {
        val pending = quote(QuoteState.PENDING)
        val issued = quote(QuoteState.ISSUED)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(pending, issued)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(issued, result)
        assertEquals(1_000L, testScheduler.currentTime)
        verify(wallet, times(2)).checkMintQuote(quoteId)
    }

    @Test
    fun `unpaid quote stops after five checks`() = runTest {
        val unpaid = quote(QuoteState.UNPAID)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(unpaid)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(unpaid, result)
        assertEquals(4_000L, testScheduler.currentTime)
        verify(wallet, times(5)).checkMintQuote(quoteId)
    }

    @Test
    fun `pending quote stops after five checks`() = runTest {
        val pending = quote(QuoteState.PENDING)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(pending)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(pending, result)
        assertEquals(4_000L, testScheduler.currentTime)
        verify(wallet, times(5)).checkMintQuote(quoteId)
    }

    @Test
    fun `paid quote on final attempt succeeds`() = runTest {
        val unpaid = quote(QuoteState.UNPAID)
        val paid = quote(QuoteState.PAID)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(unpaid, unpaid, unpaid, unpaid, paid)

        val result = SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)

        assertSame(paid, result)
        assertEquals(4_000L, testScheduler.currentTime)
        verify(wallet, times(5)).checkMintQuote(quoteId)
    }

    @Test
    fun `quote check error propagates without retrying`() = runTest {
        val error = IOException("Mint unavailable")
        whenever(wallet.checkMintQuote(quoteId)).thenAnswer { throw error }

        val failure = runCatching {
            SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)
        }.exceptionOrNull()

        assertSame(error, failure)
        assertEquals(0L, testScheduler.currentTime)
        verify(wallet).checkMintQuote(quoteId)
    }

    @Test
    fun `cancellation during retry delay stops checking`() = runTest {
        val pending = quote(QuoteState.PENDING)
        whenever(wallet.checkMintQuote(quoteId)).thenReturn(pending)
        val checking = launch {
            SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)
        }
        runCurrent()
        advanceTimeBy(999L)
        verify(wallet).checkMintQuote(quoteId)

        checking.cancel()
        checking.join()
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(checking.isCancelled)
        verify(wallet).checkMintQuote(quoteId)
    }

    @Test
    fun `cancellation during quote check propagates without retrying`() = runTest {
        val cancelled = CancellationException("Checkout closed")
        whenever(wallet.checkMintQuote(quoteId)).thenThrow(cancelled)

        val failure = runCatching {
            SwapToLightningMintManager.checkLightningMintQuoteWithRetry(wallet, quoteId)
        }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertEquals(0L, testScheduler.currentTime)
        verify(wallet).checkMintQuote(quoteId)
    }

    private fun quote(state: QuoteState): MintQuote = mock<MintQuote>().also {
        whenever(it.state).thenReturn(state)
    }
}
