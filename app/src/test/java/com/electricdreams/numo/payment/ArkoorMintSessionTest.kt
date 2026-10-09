package com.electricdreams.numo.payment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintInfo
import org.cashudevkit.MintMethodSettings
import org.cashudevkit.MintQuote
import org.cashudevkit.Nut04Settings
import org.cashudevkit.Nuts
import org.cashudevkit.PaymentMethod
import org.cashudevkit.Proof
import org.cashudevkit.WalletInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ArkoorMintSessionTest {
    private val wallet = mock<WalletInterface>()
    private val method = ArkoorMintSession.METHOD

    private fun quote(paid: ULong = 0u, issued: ULong = 0u): MintQuote = mock<MintQuote>().also {
        whenever(it.id).thenReturn("quote")
        whenever(it.request).thenReturn("ark1testaddress")
        whenever(it.paymentMethod).thenReturn(method)
        whenever(it.unit).thenReturn(CurrencyUnit.Sat)
        whenever(it.amount).thenReturn(Amount(1_000u))
        whenever(it.amountPaid).thenReturn(Amount(paid))
        whenever(it.amountIssued).thenReturn(Amount(issued))
        whenever(it.expiry).thenReturn(0u)
    }

    private suspend fun supportArkoor() {
        val nuts = mock<Nuts>()
        val info = mock<MintInfo>()
        whenever(info.nuts).thenReturn(nuts)
        whenever(nuts.nut04).thenReturn(Nut04Settings(listOf(
            MintMethodSettings(method, CurrencyUnit.Sat, "arkoor", null, null, false)
        ), false))
        whenever(wallet.fetchMintInfo()).thenReturn(info)
    }

    @Test
    fun `creates arkoor quote and waits for full amount before issuing proofs`() = runTest {
        supportArkoor()
        val initial = quote()
        val partial = quote(500u)
        val paid = quote(1_000u)
        whenever(wallet.mintQuote(method, Amount(1_000u), null, null)).thenReturn(initial)
        whenever(wallet.checkMintQuote("quote")).thenReturn(partial, paid)
        val proof = mock<Proof>()
        whenever(proof.amount).thenReturn(Amount(1_000u))
        whenever(wallet.mint(any(), any(), anyOrNull())).thenReturn(listOf(proof))
        var request = ""
        ArkoorMintSession(wallet, 1).receive(1_000, onRequestReady = {
            request = it.request
        }, onRetry = { throw it })
        assertEquals("ark1testaddress", request)
        verify(wallet).mintQuote(method, Amount(1_000u), null, null)
        verify(wallet).mint("quote", org.cashudevkit.SplitTarget.None, null)
    }

    @Test
    fun `resume keeps existing quote and retries a transient status failure`() = runTest {
        val initial = quote()
        val issued = quote(1_000u, 1_000u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(initial)
            .thenThrow(RuntimeException("offline")).thenReturn(issued)
        var retries = 0
        ArkoorMintSession(wallet, 1).receive(1_000, "quote", {}, { retries++ })
        assertEquals(1, retries)
        verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(wallet, never()).mint(any(), any(), anyOrNull())
    }

    @Test
    fun `resume retries the first status check without replacing the saved quote`() = runTest {
        val initial = quote()
        val issued = quote(1_000u, 1_000u)
        whenever(wallet.checkMintQuote("quote"))
            .thenThrow(RuntimeException("offline"))
            .thenThrow(RuntimeException("still offline"))
            .thenReturn(initial, issued)
        var retries = 0
        var ready = 0
        ArkoorMintSession(wallet, 1).receive(1_000, "quote", { ready++ }, { retries++ })
        assertEquals(2, retries)
        assertEquals(1, ready)
        verify(wallet, times(4)).checkMintQuote("quote")
        verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `websocket updates trigger checks and partial payments never complete checkout`() = runTest {
        val initial = quote()
        val partial = quote(500u)
        val paid = quote(1_000u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(initial, initial, partial, paid)
        val proof = mock<Proof>()
        whenever(proof.amount).thenReturn(Amount(1_000u))
        whenever(wallet.mint(any(), any(), anyOrNull())).thenReturn(listOf(proof))
        var closed = false
        val updates = MintQuoteWebSocket.Subscription("quote", "arkoor_mint_quote") { closed = true }
        updates.isConnected = true
        val session = ArkoorMintSession(wallet, subscribeToQuote = { updates })
        val receiving = launch { session.receive(1_000, "quote", {}, { throw it }) }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        verify(wallet, times(2)).checkMintQuote("quote")
        updates.signal()
        runCurrent()
        verify(wallet, times(3)).checkMintQuote("quote")
        verify(wallet, never()).mint(any(), any(), anyOrNull())
        assertTrue(receiving.isActive)
        updates.signal()
        runCurrent()
        assertTrue(receiving.isCompleted)
        assertTrue(closed)
        verify(wallet).mint("quote", org.cashudevkit.SplitTarget.None, null)
    }

    @Test
    fun `unavailable websocket falls back to checks on the same quote`() = runTest {
        val initial = quote()
        val issued = quote(1_000u, 1_000u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(initial, initial, issued)
        var closed = false
        val updates = MintQuoteWebSocket.Subscription("quote", "arkoor_mint_quote") { closed = true }
        ArkoorMintSession(wallet, 1, subscribeToQuote = { updates })
            .receive(1_000, "quote", {}, { throw it })
        verify(wallet, times(3)).checkMintQuote("quote")
        assertTrue(closed)
        verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `cancellation during first saved quote check does not retry`() = runTest {
        whenever(wallet.checkMintQuote("quote")).thenThrow(CancellationException("closed"))
        var retries = 0
        val failure = runCatching {
            ArkoorMintSession(wallet, 1).receive(1_000, "quote", {}, { retries++ })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, retries)
        verify(wallet).checkMintQuote("quote")
        verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `issued quote waits for interrupted mint recovery`() = runTest {
        val initial = quote()
        val recovering = quote(1_000u, 1_000u)
        whenever(recovering.usedByOperation).thenReturn("mint-operation")
        val recovered = quote(1_000u, 1_000u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(initial, recovering, recovered)
        ArkoorMintSession(wallet, 1).receive(1_000, "quote", {}, { throw it })
        verify(wallet, org.mockito.kotlin.times(3)).checkMintQuote("quote")
        verify(wallet, never()).mint(any(), any(), anyOrNull())
    }

    @Test
    fun `failed issuance retries the same paid quote`() = runTest {
        val paid = quote(1_000u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(paid)
        val proof = mock<Proof>()
        whenever(proof.amount).thenReturn(Amount(1_000u))
        whenever(wallet.mint(any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("connection lost")).thenReturn(listOf(proof))
        var retries = 0
        ArkoorMintSession(wallet, 1).receive(1_000, "quote", {}, { retries++ })
        assertEquals(1, retries)
        verify(wallet, org.mockito.kotlin.times(2)).mint("quote", org.cashudevkit.SplitTarget.None, null)
        verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `unpaid expired quote fails but late paid quote can be issued`() = runTest {
        val expired = quote()
        whenever(expired.expiry).thenReturn(1u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(expired)
        val failure = runCatching {
            ArkoorMintSession(wallet, 1) { 2u }.receive(1_000, "quote", {}, {})
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        verify(wallet, never()).mint(any(), any(), anyOrNull())

        val late = quote(1_000u)
        whenever(late.expiry).thenReturn(1u)
        whenever(wallet.checkMintQuote("quote")).thenReturn(late)
        val proof = mock<Proof>()
        whenever(proof.amount).thenReturn(Amount(1_000u))
        whenever(wallet.mint(any(), any(), anyOrNull())).thenReturn(listOf(proof))
        ArkoorMintSession(wallet, 1) { 2u }.receive(1_000, "quote", {}, { throw it })
        verify(wallet).mint("quote", org.cashudevkit.SplitTarget.None, null)
    }

    @Test
    fun `rejects lightning quote on resume`() = runTest {
        val wrong = quote()
        whenever(wrong.paymentMethod).thenReturn(PaymentMethod.Bolt11)
        whenever(wallet.checkMintQuote("quote")).thenReturn(wrong)
        val failure = runCatching {
            ArkoorMintSession(wallet).receive(1_000, "quote", {}, {})
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        verify(wallet, never()).mint(any(), any(), anyOrNull())
    }

    @Test
    fun `cancellation stops polling without an error callback`() = runTest {
        val initial = quote()
        whenever(wallet.checkMintQuote("quote")).thenReturn(initial)
            .thenThrow(CancellationException("closed"))
        var retries = 0
        val failure = runCatching {
            ArkoorMintSession(wallet).receive(1_000, "quote", {}, { retries++ })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, retries)
    }
}
