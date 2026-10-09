package com.electricdreams.numo

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.core.update.UpdateOperationGate
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.ndef.NdefHostCardEmulationService
import com.electricdreams.numo.payment.LightningMintHandler
import com.electricdreams.numo.payment.MintQuoteWebSocket
import com.electricdreams.numo.payment.NostrPaymentHandler
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.Wallet
import org.cashudevkit.WalletRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestActivityPaymentTest {
    private lateinit var activity: PaymentRequestActivity
    private val withdrawals = mock<AutoWithdrawManager>()

    @Before
    fun setUp() {
        // Attach the activity without starting quote creation or other payment services.
        activity = Robolectric.buildActivity(PaymentRequestActivity::class.java).get()
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", withdrawals)
        ReflectionHelpers.setField(activity, "paymentAmount", 1_000L)
        ReflectionHelpers.setField(activity, "formattedAmountString", "1,000 sats")
        ReflectionHelpers.setField(activity, "statusText", TextView(activity))
        ReflectionHelpers.setField(activity, "nfcAnimationContainer", FrameLayout(activity))
        ReflectionHelpers.setField(activity, "nfcAnimationView", mock<NfcPaymentAnimationView>())
        ReflectionHelpers.setField(activity, "animationResultAmountText", TextView(activity))
        ReflectionHelpers.setField(activity, "animationResultLabelText", TextView(activity))
        ReflectionHelpers.setField(activity, "animationActionsContainer", View(activity))
        ReflectionHelpers.setField(activity, "animationViewDetailsButton", TextView(activity))
        ReflectionHelpers.setField(activity, "animationCloseButton", TextView(activity))
    }

    @After
    fun tearDown() {
        ReflectionHelpers.getField<Job?>(activity, "arkoorJob")?.cancel()
        ReflectionHelpers.getField<MintQuoteWebSocket?>(activity, "quoteSockets")?.close()
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
    }

    @Test
    fun `usd checkout keeps its formatted minor unit amount`() {
        assertLocalCheckoutAmount("usd", 100L, "$1.00", "$1.00")
    }

    @Test
    fun `eur checkout keeps its formatted minor unit amount`() {
        assertLocalCheckoutAmount("eur", 100L, "€1,00", "€1,00")
    }

    @Test
    fun `custom unit checkout keeps its formatted amount`() {
        assertLocalCheckoutAmount("points", 100L, "100 points", "100 points")
    }

    @Test
    fun `sat checkout with fiat input shows exact sats`() {
        assertLocalCheckoutAmount("sat", 1_000L, "$1.00", "1,000 sat")
    }

    @Test
    fun `resumed dollar checkout keeps dollars after preference changes to sats`() {
        activity.intent = resumeIntent("usd", 100L)
        assertLocalCheckoutAmount("sat", 100L, "$1.00", "$1.00", expectedUnit = "usd")
    }

    @Test
    fun `resumed sat checkout uses sats for every payment option after preference changes`() {
        activity.intent = resumeIntent("sat", 1_000L)
        assertLocalCheckoutAmount("usd", 1_000L, "$1.00", "1,000 sat", expectedUnit = "sat")
    }

    @Test
    fun `old arkoor resume intent without unit still uses sats`() {
        activity.intent = Intent().putExtra(PaymentRequestActivity.EXTRA_ARKOOR_QUOTE_ID, "saved-quote")
        ReflectionHelpers.setField(activity, "resumeArkoorQuoteId", "saved-quote")
        assertLocalCheckoutAmount("usd", 1_000L, "$1.00", "1,000 sat", expectedUnit = "sat")
    }

    @Test
    fun `resumed arkoor quote is checked in its saved sats wallet despite dollar preference`(): Unit = runBlocking {
        val manager = mock<MintManager>()
        whenever(manager.getPreferredUnit()).thenReturn("usd")
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", manager)
        val repository = mock<WalletRepository>()
        val wallet = mock<Wallet>()
        val mintUrl = "https://arkoor.test"
        val quoteChecked = CompletableDeferred<Unit>()
        whenever(repository.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)).thenReturn(wallet)
        whenever(wallet.checkMintQuote("saved-quote")).doSuspendableAnswer {
            quoteChecked.complete(Unit)
            awaitCancellation()
        }
        activity.intent = resumeIntent("sat", 1_000L)
        ReflectionHelpers.setField(activity, "resumeArkoorQuoteId", "saved-quote")
        ReflectionHelpers.setField(activity, "resumeArkoorMintUrl", mintUrl)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "initializePaymentUnit")

        val previousWallet = CashuWalletManager.getWallet()
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", repository)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            ReflectionHelpers.callInstanceMethod<Void>(activity, "startArkoorPaymentFlow")
            withTimeout(5_000) { quoteChecked.await() }
            assertFalse("An active Arkoor operation must block installation",
                UpdateOperationGate.shared.tryBeginInstall())
            verify(repository).getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
            verify(wallet).checkMintQuote("saved-quote")
            verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
        } finally {
            ReflectionHelpers.getField<Job?>(activity, "arkoorJob")?.cancelAndJoin()
            ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", previousWallet)
            Dispatchers.resetMain()
            UpdateOperationGate.shared.finishInstall()
        }
        try {
            assertTrue("Cancelling Arkoor must release its update guard",
                UpdateOperationGate.shared.tryBeginInstall())
        } finally {
            UpdateOperationGate.shared.finishInstall()
        }
    }

    @Test
    fun `offline resume renders saved payment methods before arkoor status is available`(): Unit = runBlocking {
        val entry = PaymentHistoryEntry.createPending(
            amount = 1_000L, entryUnit = "sat", enteredAmount = 1_000L,
            bitcoinPrice = null, paymentRequest = "creqAsaved", formattedAmount = "1,000 sat",
        ).copy(
            arkoorAddress = "ark1saved", arkoorQuoteId = "saved-quote",
            arkoorMintUrl = "https://arkoor.test", lightningInvoice = "lnbc1saved",
        )
        activity.intent = PaymentIntentFactory.createResumePaymentIntent(activity, entry)
        ReflectionHelpers.setField(activity, "resumeArkoorQuoteId", activity.intent.getStringExtra(
            PaymentRequestActivity.EXTRA_ARKOOR_QUOTE_ID,
        ))
        ReflectionHelpers.setField(activity, "resumeArkoorMintUrl", activity.intent.getStringExtra(
            PaymentRequestActivity.EXTRA_ARKOOR_MINT_URL,
        ))
        ReflectionHelpers.setField(activity, "lightningInvoice", activity.intent.getStringExtra(
            PaymentRequestActivity.EXTRA_LIGHTNING_INVOICE,
        ))
        ReflectionHelpers.setField(activity, "hcePaymentRequest", entry.paymentRequest)
        val tabs = mock<PaymentTabManager>()
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.UNIFIED)
        ReflectionHelpers.setField(activity, "tabManager", tabs)
        ReflectionHelpers.setField(activity, "isDualQuoteCheckout", true)
        ReflectionHelpers.setField(activity, "arkoorLoading", true)
        ReflectionHelpers.setField(activity, "lightningStarted", true)
        val qr = ImageView(activity).apply { visibility = View.GONE }
        val spinner = View(activity)
        ReflectionHelpers.setField(activity, "unifiedQrImageView", qr)
        ReflectionHelpers.setField(activity, "unifiedLoadingSpinner", spinner)

        val manager = mock<MintManager>()
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", manager)
        val monitor = mock<ConnectivityMonitor>()
        whenever(monitor.isOnlineNow()).thenReturn(false)
        ReflectionHelpers.setStaticField(ConnectivityMonitor::class.java, "instance", monitor)
        val repository = mock<WalletRepository>()
        val wallet = mock<Wallet>()
        whenever(repository.getWallet(MintUrl(checkNotNull(entry.arkoorMintUrl)), CurrencyUnit.Sat)).thenReturn(wallet)
        val checked = CompletableDeferred<Unit>()
        whenever(wallet.checkMintQuote("saved-quote")).doSuspendableAnswer {
            checked.complete(Unit)
            throw IOException("Offline")
        }
        val previousWallet = CashuWalletManager.getWallet()
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", repository)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            ReflectionHelpers.callInstanceMethod<Void>(activity, "startArkoorPaymentFlow")
            withTimeout(5_000) { checked.await() }
            val request = ReflectionHelpers.callInstanceMethod<String?>(activity, "unifiedPaymentRequest")
                ?: throw AssertionError("Saved payment methods must be available while offline")
            assertTrue(request.contains("ARK=ARK1SAVED"))
            assertTrue(request.contains("LIGHTNING=LNBC1SAVED"))
            assertTrue(request.contains("CREQ=creqAsaved"))
            assertEquals(View.VISIBLE, qr.visibility)
            assertNotNull(qr.drawable)
            assertEquals(View.GONE, spinner.visibility)
            assertFalse(ReflectionHelpers.getField(activity, "hasTerminalOutcome"))
            assertTrue(ReflectionHelpers.getField<Job>(activity, "arkoorJob").isActive)
            verify(wallet, never()).mintQuote(any(), anyOrNull(), anyOrNull(), anyOrNull())
            verify(wallet, never()).mint(any(), any(), anyOrNull())
        } finally {
            ReflectionHelpers.getField<Job?>(activity, "arkoorJob")?.cancelAndJoin()
            ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", previousWallet)
            ReflectionHelpers.setStaticField(ConnectivityMonitor::class.java, "instance", null)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `arkoor success without lightning quote passes receiving mint to auto withdrawal once`() {
        ReflectionHelpers.setField(activity, "arkoorMintUrl", "https://arkoor.test")
        completePayment(PaymentHistoryEntry.TYPE_ARKOOR)
        displaySuccess()
        displaySuccess()
        verify(withdrawals, times(1)).onPaymentReceived("", "https://arkoor.test")
    }

    @Test
    fun `arkoor success uses its mint when lightning belongs to another mint`() {
        ReflectionHelpers.setField(activity, "arkoorMintUrl", "https://arkoor.test")
        ReflectionHelpers.setField(activity, "lightningMintUrl", "https://lightning.test")
        completePayment(PaymentHistoryEntry.TYPE_ARKOOR)
        displaySuccess()
        verify(withdrawals).onPaymentReceived("", "https://arkoor.test")
    }

    @Test
    fun `lightning success keeps its receiving mint for auto withdrawal`() {
        ReflectionHelpers.setField(activity, "arkoorMintUrl", "https://arkoor.test")
        ReflectionHelpers.setField(activity, "lightningMintUrl", "https://lightning.test")
        completePayment(PaymentHistoryEntry.TYPE_LIGHTNING)
        displaySuccess()
        verify(withdrawals).onPaymentReceived("", "https://lightning.test")
    }

    private fun assertLocalCheckoutAmount(
        unit: String, amount: Long, formatted: String, expected: String,
        expectedUnit: String = unit,
    ) {
        val mintManager = mock<MintManager>()
        whenever(mintManager.getPreferredUnit()).thenReturn(unit)
        whenever(mintManager.getAllowedMints()).thenReturn(emptyList())
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", mintManager)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "initializePaymentUnit")
        ReflectionHelpers.setField(activity, "paymentAmount", amount)
        ReflectionHelpers.setField(activity, "formattedAmountString", formatted)
        val amountDisplay = TextView(activity).apply { text = formatted }
        val convertedDisplay = TextView(activity).apply { visibility = View.VISIBLE }
        ReflectionHelpers.setField(activity, "largeAmountDisplay", amountDisplay)
        ReflectionHelpers.setField(activity, "convertedAmountDisplay", convertedDisplay)
        ReflectionHelpers.setField(activity, "tabManager", mock<PaymentTabManager>())

        // Exercise checkout initialization while keeping payment services offline.
        mockConstruction(LightningMintHandler::class.java) { _, constructor ->
            assertEquals(expectedUnit, constructor.arguments()[6])
        }.use {
            mockConstruction(NostrPaymentHandler::class.java) { _, constructor ->
                assertEquals(expectedUnit, constructor.arguments()[2])
            }.use {
                mockStatic(NdefHostCardEmulationService::class.java).use {
                    ReflectionHelpers.callInstanceMethod<Void>(activity, "initializeLocalPaymentRequest")
                }
            }
        }

        assertEquals(expected, amountDisplay.text.toString())
        assertEquals(View.GONE, convertedDisplay.visibility)
    }

    private fun resumeIntent(unit: String, amount: Long): Intent {
        val entry = PaymentHistoryEntry.createPending(
            amount = amount, entryUnit = "usd", enteredAmount = 100L,
            bitcoinPrice = null, paymentRequest = null, formattedAmount = "$1.00",
            ecashUnit = unit,
        )
        return PaymentIntentFactory.createResumePaymentIntent(activity, entry)
    }

    private fun completePayment(type: String) {
        PaymentRequestActivity::class.java.getDeclaredMethod(
            "handleLightningPaymentSuccess", String::class.java, String::class.java,
        ).apply { isAccessible = true }.invoke(activity, type, null)
    }

    private fun displaySuccess() {
        PaymentRequestActivity::class.java.getDeclaredMethod(
            "onNfcAnimationResultDisplayed", Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(activity, true)
    }
}
