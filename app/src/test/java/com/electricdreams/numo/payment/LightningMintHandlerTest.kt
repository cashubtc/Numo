package com.electricdreams.numo.payment

import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import org.cashudevkit.MultiMintWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.mockito.kotlin.anyOrNull
import org.cashudevkit.Amount
import org.cashudevkit.MintQuote
import org.cashudevkit.MintUrl
import org.cashudevkit.Proof
import org.cashudevkit.QuoteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.atMostOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.internal.verification.VerificationModeFactory
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LightningMintHandlerTest {

    @Mock
    private lateinit var mockWallet: MultiMintWallet
    @Mock
    private lateinit var mockCallback: LightningMintHandler.Callback
    @Mock
    private lateinit var mockMintQuote: MintQuote
    
    private lateinit var mockWebServer: MockWebServer
    private lateinit var testScope: TestScope
    private lateinit var handler: LightningMintHandler
    private lateinit var cashuWalletManagerMock: org.mockito.MockedStatic<CashuWalletManager>
    private lateinit var logMock: org.mockito.MockedStatic<Log>
    
    // Test data
    private val mintUrlStr = "http://localhost:8080" // Port will be updated
    private val preferredMint = "http://localhost:8080"
    private val allowedMints = listOf("http://localhost:8080")
    private val quoteId = "quote123"
    private val bolt11 = "lnbc1..."
    private val paymentAmount = 1000L

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        // Setup coroutines
        Dispatchers.setMain(UnconfinedTestDispatcher())
        testScope = TestScope(UnconfinedTestDispatcher())
        
        // start mock web server
        mockWebServer = MockWebServer()
        mockWebServer.start()
        val serverUrl = mockWebServer.url("/").toString()
        val useUrl = serverUrl.removeSuffix("/")
        
        // Mock static CashuWalletManager
        cashuWalletManagerMock = mockStatic(CashuWalletManager::class.java)
        cashuWalletManagerMock.`when`<MultiMintWallet> { CashuWalletManager.getWallet() }.thenReturn(mockWallet)
        
        // Setup handler with dynamic server url and injected dispatcher
        handler = LightningMintHandler(useUrl, listOf(useUrl), testScope, UnconfinedTestDispatcher())
        
        // Setup default mock behaviors
        `when`(mockMintQuote.request).thenReturn(bolt11)
        `when`(mockMintQuote.id).thenReturn(quoteId)
        `when`(mockMintQuote.state).thenReturn(QuoteState.UNPAID)
        
        // Silence logs
        logMock = mockStatic(Log::class.java)
    }

    @After
    fun tearDown() {
        cashuWalletManagerMock.close()
        logMock.close()
        mockWebServer.shutdown()
    }

    @Test
    fun startFailsWhenWalletIsNotReady() {
        runBlocking {
            // Unset wallet
            cashuWalletManagerMock.close()
            cashuWalletManagerMock = mockStatic(CashuWalletManager::class.java)
            cashuWalletManagerMock.`when`<MultiMintWallet> { CashuWalletManager.getWallet() }.thenReturn(null)
            
            // Re-setup logs if needed since we closed mocks
            
            handler.start(paymentAmount, mockCallback)
            
            // Then
            verify(mockCallback).onError("Wallet not ready")
            verify(mockWallet, never()).mintQuote(any(), any(), any())
        }
    }

    @Test
    fun startFailsWhenNoMintsConfigured() {
        runBlocking {
            // Given
            val emptyHandler = LightningMintHandler(null, emptyList(), testScope)
            
            // When
            emptyHandler.start(paymentAmount, mockCallback)
            
            // Then
            verify(mockCallback).onError("No mints configured")
        }
    }

    @Test
    fun startFailsWithInvalidMintURL() {
        runBlocking {
            // Re-assert that wallet IS ready (from setup), so we pass the first check
            
            cashuWalletManagerMock.`when`<MultiMintWallet> { CashuWalletManager.getWallet() }.thenReturn(mockWallet)

            // Stub wallet to throw if called, avoiding NPE and verifying error propagation
            `when`(mockWallet.mintQuote(any(), any(), any())).thenThrow(RuntimeException("Wallet rejected URL"))

            // Given invalid mint URL
            val invalidHandler = LightningMintHandler("http://exa mple.com", listOf("http://exa mple.com"), testScope, UnconfinedTestDispatcher())
            
            // When
            invalidHandler.start(paymentAmount, mockCallback)

            testScope.advanceUntilIdle()
            
            // Then - verify that we get an error (either from validation or wallet)
            verify(mockCallback).onError(any())
        }
    }

    @Test
    fun pollingDetectsPaymentAndCallsMint() {
        runBlocking {
            // Setup handler with StandardTestDispatcher to control time for polling
            val testDispatcher = StandardTestDispatcher()
            val testScope = TestScope(testDispatcher)
            
            // Use StandardTestDispatcher for the IO dispatcher too so we can control time
            val handler = LightningMintHandler(mintUrlStr, listOf(mintUrlStr), testScope, testDispatcher)

            // We stub mintQuote to return successfully
            doReturn(mockMintQuote).`when`(mockWallet).mintQuote(anyOrNull(), anyOrNull(), anyOrNull())
            
            // First check returns unpaid
            doReturn(mockMintQuote).`when`(mockWallet).checkMintQuote(org.mockito.kotlin.any(), org.mockito.kotlin.any())
            
            // Start process
            handler.start(paymentAmount, mockCallback)
            testDispatcher.scheduler.runCurrent()
            
            // Verify invoice ready called
            verify(mockCallback).onInvoiceReady(any(), any(), any())
            
            // Prepare "Paid" response for subsequent polling
            val paidQuote = mock(MintQuote::class.java)
            `when`(paidQuote.state).thenReturn(QuoteState.PAID)
            
            // Wait - we need to ensure the coroutines that are polling get to run
            // We'll update the mock to return PAID for checkMintQuote
            doReturn(paidQuote).`when`(mockWallet).checkMintQuote(org.mockito.kotlin.any(), org.mockito.kotlin.any())
            
            // And return proofs for mint
            doReturn(listOf<Proof>()).`when`(mockWallet).mint(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())
            
            // Advance time to trigger poll: 10 * 5s = 50s.
            testDispatcher.scheduler.advanceTimeBy(LightningMintHandler.POLL_INTERVAL_MS * 10)
            testDispatcher.scheduler.runCurrent()
            testScope.advanceUntilIdle() // Ensure all coroutines finish
            
            // Verify mint called
            verify(mockWallet, atLeastOnce()).mint(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull())
            verify(mockCallback).onPaymentSuccess()
        }
    }
}
