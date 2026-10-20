package com.electricdreams.numo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.HistoryEntry
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.cashudevkit.MintQuote
import org.cashudevkit.QuoteState
import org.cashudevkit.Wallet
import org.cashudevkit.WalletRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SwapPaymentConfirmationUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mints: MintManager = mock()
    private val withdrawals: AutoWithdrawManager = mock()
    private val previousDispatcher = PaymentsHistoryActivity.ioDispatcher
    private val previousWallet = CashuWalletManager.getWallet()
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        PaymentsHistoryActivity.ioDispatcher = Dispatchers.Unconfined
        AppGlobals.init(context)
        CashuWalletManager.appContext = context
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", mints)
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", withdrawals)
        whenever(mints.getPreferredUnit()).thenReturn("sat")
        whenever(mints.getAllowedMints()).thenReturn(emptyList())
        val repository: WalletRepository = mock()
        val destination: Wallet = mock()
        val quote: MintQuote = mock()
        whenever(repository.getWallet(any(), any())).thenReturn(destination)
        whenever(destination.checkMintQuote(any())).thenReturn(quote)
        whenever(quote.state).thenReturn(QuoteState.ISSUED)
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", repository)
        context.getSharedPreferences("PaymentHistory", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PaymentsHistoryActivity.ioDispatcher = previousDispatcher
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", previousWallet)
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
        Dispatchers.resetMain()
    }

    @Test
    fun `confirming a resumed swap passes its destination to auto withdrawal once`() {
        val entry = pendingSwap("sat", 1_000L)
        val intent = PaymentIntentFactory.createResumePaymentIntent(context, entry)
        Robolectric.buildActivity(PaymentRequestActivity::class.java, intent).setup().use {
            val activity = it.get()
            val rendered = CompletableDeferred<Unit>()
            val animation: NfcPaymentAnimationView = mock()
            doAnswer { rendered.complete(Unit); null }.whenever(animation).showSuccess(any())
            ReflectionHelpers.setField(activity, "nfcAnimationView", animation)

            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            dialog.findViewById<Button>(android.R.id.button3).performClick()
            awaitUi { rendered.isCompleted }
            repeat(2) {
                ReflectionHelpers.callInstanceMethod<Void>(activity, "onNfcAnimationResultDisplayed",
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            }
            verify(withdrawals, times(1)).onPaymentReceived("", "https://destination.example")
        }
    }

    @Test
    fun `history confirms sat swap with saved unit after preference changes to dollars`() {
        confirmFromHistory("sat", "usd", 1_000L)
    }

    @Test
    fun `history confirms dollar swap with saved unit after preference changes to sats`() {
        confirmFromHistory("usd", "sat", 100L)
    }

    @Test
    fun `receipt honors explicit saved unit with an empty token`() {
        whenever(mints.getPreferredUnit()).thenReturn("usd")
        val intent = Intent(context, PaymentReceivedActivity::class.java)
            .putExtra(PaymentReceivedActivity.EXTRA_TOKEN, "")
            .putExtra(PaymentReceivedActivity.EXTRA_AMOUNT, 1_000L)
            .putExtra(PaymentReceivedActivity.EXTRA_UNIT, "sat")
        assertReceipt(intent, "sat", 1_000L)
    }

    @Test
    fun `receipt without a saved unit keeps using the current preference`() {
        whenever(mints.getPreferredUnit()).thenReturn("usd")
        val intent = Intent(context, PaymentReceivedActivity::class.java)
            .putExtra(PaymentReceivedActivity.EXTRA_TOKEN, "")
            .putExtra(PaymentReceivedActivity.EXTRA_AMOUNT, 100L)
        assertReceipt(intent, "usd", 100L)
    }

    private fun confirmFromHistory(unit: String, preferredUnit: String, amount: Long) {
        val entry = pendingSwap(unit, amount)
        whenever(mints.getPreferredUnit()).thenReturn(preferredUnit)
        Robolectric.buildActivity(PaymentsHistoryActivity::class.java).setup().use {
            val activity = it.get()
            ReflectionHelpers.callInstanceMethod<Void>(activity, "handleEntryClick",
                ReflectionHelpers.ClassParameter.from(HistoryEntry::class.java, entry),
                ReflectionHelpers.ClassParameter.from(View::class.java, View(activity)))
            var receipt: Intent? = null
            awaitUi {
                receipt = receipt ?: shadowOf(activity).nextStartedActivity
                receipt != null
            }
            val intent = requireNotNull(receipt)
            assertEquals(unit, intent.getStringExtra(PaymentReceivedActivity.EXTRA_UNIT))
            assertReceipt(intent, unit, amount)
        }
    }

    private fun pendingSwap(unit: String, amount: Long): PaymentHistoryEntry {
        whenever(mints.getPreferredUnit()).thenReturn(unit)
        val formatted = Amount(amount, Amount.Currency.fromCode(unit)).toString()
        val id = PaymentsHistoryActivity.addPendingPayment(context, amount, unit, amount,
            null, null, formatted)
        PaymentsHistoryActivity.recordPendingSwap(context, id, amount, unit,
            PaymentHistoryEntry.Companion.SwapToLightningMintFrame(
                "https://source.example", "melt", "https://destination.example", "mint", true))
        return requireNotNull(PaymentsHistoryActivity.getPaymentEntryById(context, id))
    }

    private fun assertReceipt(intent: Intent, unit: String, amount: Long) {
        Robolectric.buildActivity(PaymentReceivedActivity::class.java, intent).setup().use {
            val activity = it.get()
            val formatted = Amount(amount, Amount.Currency.fromCode(unit)).toString()
            assertEquals(activity.getString(R.string.payment_received_amount, formatted),
                activity.findViewById<TextView>(R.id.amount_received_text).text.toString())
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Payment UI did not finish updating", condition())
    }
}
