package com.electricdreams.numo

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab
import com.electricdreams.numo.payment.card.CardAmountResolver
import com.electricdreams.numo.payment.card.StripeTerminalManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaymentRequestActivityCardTest {
    private lateinit var activity: PaymentRequestActivity
    private val tabs = mock<PaymentTabManager>()
    private lateinit var cardStatus: TextView
    private lateinit var cardProgress: ProgressBar
    private lateinit var cardAction: MaterialButton
    private lateinit var shareButton: View

    @Before
    fun setUp() {
        // Attach the activity without starting quote creation or other payment services.
        activity = Robolectric.buildActivity(PaymentRequestActivity::class.java).get()
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", mock<AutoWithdrawManager>())
        cardStatus = TextView(activity)
        cardProgress = ProgressBar(activity)
        cardAction = MaterialButton(ContextThemeWrapper(activity, R.style.Theme_Numo))
        shareButton = View(activity)
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTab.CARD)
        mapOf(
            "tabManager" to tabs,
            "unifiedTab" to LinearLayout(activity),
            "paymentAmount" to 12_345L,
            "statusText" to TextView(activity),
            "shareButton" to shareButton,
            "cardContainer" to FrameLayout(activity),
            "cardStatusText" to cardStatus,
            "cardProgress" to cardProgress,
            "cardActionButton" to cardAction,
            "nfcAnimationContainer" to FrameLayout(activity),
            "nfcAnimationView" to mock<NfcPaymentAnimationView>(),
            "animationResultAmountText" to TextView(activity),
            "animationResultLabelText" to TextView(activity),
            "animationActionsContainer" to View(activity),
            "animationViewDetailsButton" to TextView(activity),
            "animationCloseButton" to TextView(activity),
        ).forEach { (field, value) -> ReflectionHelpers.setField(activity, field, value) }
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
        setCharging(false)
    }

    @Test
    fun `card success records one completed card payment with the PaymentIntent id`() {
        val paymentId = PaymentsHistoryActivity.addPendingPayment(
            context = activity,
            amount = 12_345L,
            entryUnit = "usd",
            enteredAmount = 1_250L,
            bitcoinPrice = 100_000.0,
            paymentRequest = null,
            formattedAmount = "$12.50",
        )
        ReflectionHelpers.setField(activity, "pendingPaymentId", paymentId)
        ReflectionHelpers.setField(
            activity,
            "cardAmount",
            CardAmountResolver.CardAmount(minorUnits = 1_250, currency = "usd"),
        )

        call("handleCardPaymentSuccess", "pi_123")
        // A late duplicate outcome must not record the payment twice.
        call("handleCardPaymentSuccess", "pi_456")

        val entries = PaymentsHistoryActivity.getPaymentHistory(activity).filter { it.id == paymentId }
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertTrue(entry.isCompleted())
        assertEquals(PaymentHistoryEntry.TYPE_CARD, entry.paymentType)
        assertEquals("pi_123", entry.paymentRequest)
        assertEquals(1_250L, entry.cardAmountMinorUnits)
        assertEquals("usd", entry.cardCurrency)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }

    @Test
    fun `card tab stays hidden on devices without Tap to Pay`() {
        // SDK 28 is below the Android 13 that Tap to Pay requires.
        call("setupCardPayments")

        verify(tabs).setCardTabAvailable(false)
        verify(tabs, never()).setCardTabAvailable(true)
    }

    @Test
    fun `pending card attempt is restored for a safe retry`() {
        val paymentId = addPendingPayment()
        assertTrue(
            PaymentsHistoryActivity.updatePendingWithCardPaymentAttempt(
                context = activity,
                paymentId = paymentId,
                paymentIntentId = "pi_resume",
                clientSecret = "pi_resume_secret_test",
            )
        )

        val reference = call("savedCardPaymentReference") as StripeTerminalManager.CardPaymentReference

        assertEquals("pi_resume", reference.paymentIntentId)
        assertEquals("pi_resume_secret_test", reference.clientSecret)
    }

    @Test
    fun `uncertain card outcome disables bitcoin until its status is checked`() {
        val paymentId = addPendingPayment()
        val unifiedTab = ReflectionHelpers.getField<LinearLayout>(activity, "unifiedTab")

        setReviewRequired(true)

        assertFalse(unifiedTab.isEnabled)
        assertTrue(
            PaymentsHistoryActivity.getPaymentEntryById(activity, paymentId)
                ?.cardPaymentReviewRequired == true,
        )

        setReviewRequired(false)

        assertTrue(unifiedTab.isEnabled)
        assertFalse(
            PaymentsHistoryActivity.getPaymentEntryById(activity, paymentId)
                ?.cardPaymentReviewRequired == true,
        )
    }

    @Test
    fun `missing bitcoin price offers a retry instead of charging`() {
        ReflectionHelpers.setField(activity, "formattedAmountString", "12,345 sats")

        call("enterCardMode")
        assertEquals(View.GONE, cardStatus.visibility)
        call("startCardPayment")

        val message = cardStatus.text.toString()
        assertTrue(message, message.startsWith("Card amount unavailable"))
        assertEquals(View.VISIBLE, cardStatus.visibility)
        assertEquals(View.VISIBLE, cardAction.visibility)
        assertEquals(View.GONE, cardProgress.visibility)
        assertEquals(null, ReflectionHelpers.getField<Job?>(activity, "cardJob"))
    }

    @Test
    fun `card payments without a stored Stripe key are not attempted`() {
        ReflectionHelpers.setField(activity, "formattedAmountString", "$12.50")

        call("startCardPayment")

        assertEquals(activity.getString(R.string.card_payment_status_not_configured), cardStatus.text.toString())
        assertEquals(View.GONE, cardAction.visibility)
        assertEquals(null, ReflectionHelpers.getField<Job?>(activity, "cardJob"))
    }

    @Test
    fun `card tab waits for explicit initialization and leaving it restores sharing`() {
        ReflectionHelpers.setField(
            activity,
            "cardAmount",
            CardAmountResolver.CardAmount(minorUnits = 1_250, currency = "usd"),
        )

        call("enterCardMode")

        assertEquals(View.GONE, cardStatus.visibility)
        assertEquals(View.VISIBLE, cardAction.visibility)
        assertEquals(View.GONE, cardProgress.visibility)
        assertEquals(null, ReflectionHelpers.getField<Job?>(activity, "cardJob"))
        assertEquals(View.INVISIBLE, shareButton.visibility)

        val collection = Job()
        ReflectionHelpers.setField(activity, "cardJob", collection)
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTab.UNIFIED)
        call("exitCardMode")

        assertTrue(collection.isCancelled)
        assertEquals(View.VISIBLE, shareButton.visibility)
    }

    @Test
    fun `uncertain card outcome starts reconciliation when card tab opens`() {
        ReflectionHelpers.setField(
            activity,
            "cardAmount",
            CardAmountResolver.CardAmount(minorUnits = 1_250, currency = "usd"),
        )
        setReviewRequired(true)

        call("enterCardMode")

        assertEquals(activity.getString(R.string.card_payment_status_not_configured), cardStatus.text.toString())
        assertEquals(View.GONE, cardAction.visibility)
    }

    @Test
    fun `card charge that completes after checkout was cancelled is still recorded`() {
        val paymentId = addPendingPayment()
        // Backing out of checkout ends this screen's flow but keeps the sale pending.
        ReflectionHelpers.setField(activity, "hasTerminalOutcome", true)

        call("onCardPaymentCharged", "pi_late")

        val entry = PaymentsHistoryActivity.getPaymentEntryById(activity, paymentId)
        assertTrue(entry?.isCompleted() == true)
        assertEquals(PaymentHistoryEntry.TYPE_CARD, entry?.paymentType)
        assertEquals("pi_late", entry?.paymentRequest)
        assertEquals(activity.getString(R.string.card_payment_completed_late), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `card charge on a sale already paid another way asks for a refund`() {
        val paymentId = addPendingPayment()
        PaymentsHistoryActivity.completePendingPayment(
            context = activity,
            paymentId = paymentId,
            token = "cashuA",
            paymentType = PaymentHistoryEntry.TYPE_CASHU,
            mintUrl = null,
        )
        ReflectionHelpers.setField(activity, "hasTerminalOutcome", true)

        call("onCardPaymentCharged", "pi_extra")

        val entry = PaymentsHistoryActivity.getPaymentEntryById(activity, paymentId)
        assertEquals(PaymentHistoryEntry.TYPE_CASHU, entry?.paymentType)
        assertEquals(activity.getString(R.string.card_payment_also_charged), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `leaving the card tab while the card is being charged lets the charge finish`() {
        val collection = Job()
        ReflectionHelpers.setField(activity, "cardJob", collection)
        setCharging(true)

        call("exitCardMode")

        assertFalse(collection.isCancelled)
        assertEquals(collection, ReflectionHelpers.getField<Job?>(activity, "cardJob"))
    }

    private fun addPendingPayment(): String {
        val paymentId = PaymentsHistoryActivity.addPendingPayment(
            context = activity,
            amount = 12_345L,
            entryUnit = "usd",
            enteredAmount = 1_250L,
            bitcoinPrice = 100_000.0,
            paymentRequest = null,
            formattedAmount = "$12.50",
        )
        ReflectionHelpers.setField(activity, "pendingPaymentId", paymentId)
        return paymentId
    }

    private fun setReviewRequired(required: Boolean) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            activity,
            "setCardPaymentReviewRequired",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, required),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    private fun setCharging(charging: Boolean) {
        ReflectionHelpers.setStaticField(StripeTerminalManager::class.java, "isCharging", charging)
    }

    private fun call(method: String, vararg args: String): Any? =
        ReflectionHelpers.callInstanceMethod(
            activity,
            method,
            *args.map { ReflectionHelpers.ClassParameter.from(String::class.java, it) }.toTypedArray(),
        )
}
