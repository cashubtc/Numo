package com.electricdreams.numo

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaymentRequestActivityCardTest {
    private lateinit var activity: PaymentRequestActivity
    private val tabs = mock<PaymentTabManager>()
    private lateinit var cardMessage: TextView
    private lateinit var cardButton: MaterialButton

    @Before
    fun setUp() {
        // Attach the activity without starting quote creation or other payment services.
        activity = Robolectric.buildActivity(PaymentRequestActivity::class.java).get()
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", mock<AutoWithdrawManager>())
        cardMessage = TextView(activity)
        cardButton = MaterialButton(ContextThemeWrapper(activity, R.style.Theme_Numo))
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTab.UNIFIED)
        mapOf(
            "tabManager" to tabs,
            "paymentAmount" to 12_345L,
            "statusText" to TextView(activity),
            "cardMessageText" to cardMessage,
            "cardPayButton" to cardButton,
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
    fun `card button stays hidden on devices without Tap to Pay`() {
        // SDK 28 is below the Android 13 that Tap to Pay requires.
        call("setupCardPayments")

        assertEquals(View.GONE, cardButton.visibility)
        assertEquals(View.GONE, cardMessage.visibility)
    }

    @Test
    fun `card button and its message are pinned to the bottom of the screen`() {
        val root = LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Numo))
            .inflate(R.layout.activity_payment_request, null)
        val button = root.findViewById<View>(R.id.card_pay_button).layoutParams as ConstraintLayout.LayoutParams
        val message = root.findViewById<View>(R.id.card_message_text).layoutParams as ConstraintLayout.LayoutParams

        // Anchored only from below, so text of any height above them cannot move them.
        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, button.bottomToBottom)
        assertEquals(ConstraintLayout.LayoutParams.UNSET, button.topToBottom)
        assertEquals(R.id.card_pay_button, message.bottomToTop)
        assertEquals(ConstraintLayout.LayoutParams.UNSET, message.topToBottom)
    }

    @Test
    fun `card button names the action and leaves the amount to the total above`() {
        ReflectionHelpers.setField(activity, "cardAmount", CardAmountResolver.CardAmount(1200, "usd"))

        ReflectionHelpers.callInstanceMethod<Any?>(activity, "updateCardButtonLabel")

        assertEquals("Pay with card", cardButton.text.toString())
    }

    @Test
    fun `card attempt that ends without a charge explains why and offers the card again`() {
        call("showCardBusy")
        assertFalse(cardButton.isEnabled)
        assertEquals(activity.getString(R.string.card_payment_button_preparing), cardButton.text.toString())
        assertTrue(ReflectionHelpers.getField(activity, "isCardCollecting"))

        showCardIdle(activity.getString(R.string.card_payment_status_cancelled), needsStatusCheck = false)

        assertFalse(ReflectionHelpers.getField(activity, "isCardCollecting"))
        assertEquals(activity.getString(R.string.card_payment_status_cancelled), cardMessage.text.toString())
        assertTrue(cardButton.isEnabled)
        assertEquals(activity.getString(R.string.card_payment_button), cardButton.text.toString())
    }

    @Test
    fun `numo's waiting screen covers checkout while the charge is confirmed and leaves if it fails`() {
        // Leaving the overlay re-applies insets to the screen's root view.
        activity.window.setContentView(FrameLayout(activity).apply { id = R.id.payment_request_root })
        val overlay: View = ReflectionHelpers.getField(activity, "nfcAnimationContainer")
        val animationView: NfcPaymentAnimationView = ReflectionHelpers.getField(activity, "nfcAnimationView")
        overlay.visibility = View.GONE

        call("showCardConfirming")
        assertEquals(View.VISIBLE, overlay.visibility)
        verify(animationView).startProcessingDirectly(animateIn = true)
        verify(animationView, never()).startReading(false)

        showCardIdle(activity.getString(R.string.card_payment_status_unresolved), needsStatusCheck = true)
        // The waiting screen fades out over checkout before it goes.
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun `pausing while Stripe launches parks a neutral cover behind Stripe and clears it on cancel`() {
        activity.window.setContentView(FrameLayout(activity).apply { id = R.id.payment_request_root })
        val overlay: View = ReflectionHelpers.getField(activity, "nfcAnimationContainer")
        val animationView: NfcPaymentAnimationView = ReflectionHelpers.getField(activity, "nfcAnimationView")
        overlay.visibility = View.GONE

        call("showCardBusy")
        call("onCardCollectionStarting")
        call("maybeParkCardSurfaceCoverOnPause")

        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(ReflectionHelpers.getField(activity, "isCardSurfaceCovered"))
        verify(animationView).showSurfaceCover()

        showCardIdle(activity.getString(R.string.card_payment_status_cancelled), needsStatusCheck = false)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse(ReflectionHelpers.getField(activity, "isCardSurfaceCovered"))
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun `pausing during normal checkout does not park the card surface cover`() {
        val overlay: View = ReflectionHelpers.getField(activity, "nfcAnimationContainer")
        val animationView: NfcPaymentAnimationView = ReflectionHelpers.getField(activity, "nfcAnimationView")
        overlay.visibility = View.GONE

        call("maybeParkCardSurfaceCoverOnPause")

        assertEquals(View.GONE, overlay.visibility)
        assertFalse(ReflectionHelpers.getField(activity, "isCardSurfaceCovered"))
        verify(animationView, never()).showSurfaceCover()
    }

    @Test
    fun `card attempt with an unknown outcome turns the button into a status check`() {
        showCardIdle(activity.getString(R.string.card_payment_status_unresolved), needsStatusCheck = true)

        assertTrue(cardButton.isEnabled)
        assertEquals(activity.getString(R.string.card_payment_action_check), cardButton.text.toString())
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
    fun `uncertain card outcome is saved until its status is checked`() {
        val paymentId = addPendingPayment()

        setReviewRequired(true)

        assertTrue(
            PaymentsHistoryActivity.getPaymentEntryById(activity, paymentId)
                ?.cardPaymentReviewRequired == true,
        )

        setReviewRequired(false)

        assertFalse(
            PaymentsHistoryActivity.getPaymentEntryById(activity, paymentId)
                ?.cardPaymentReviewRequired == true,
        )
    }

    @Test
    fun `missing bitcoin price offers a retry instead of charging`() {
        ReflectionHelpers.setField(activity, "formattedAmountString", "12,345 sats")

        call("startCardPayment")

        val message = cardMessage.text.toString()
        assertTrue(message, message.startsWith("Card amount unavailable"))
        assertTrue(cardButton.isEnabled)
        assertEquals(null, ReflectionHelpers.getField<Job?>(activity, "cardJob"))
    }

    @Test
    fun `card payments without a stored Stripe key are not attempted`() {
        ReflectionHelpers.setField(activity, "formattedAmountString", "$12.50")

        call("startCardPayment")

        assertEquals(activity.getString(R.string.card_payment_status_not_configured), cardMessage.text.toString())
        assertFalse(cardButton.isEnabled)
        assertEquals(null, ReflectionHelpers.getField<Job?>(activity, "cardJob"))
    }

    @Test
    fun `closing checkout stops card collection`() {
        val collection = Job()
        ReflectionHelpers.setField(activity, "cardJob", collection)

        call("cancelCardCollection")

        assertTrue(collection.isCancelled)
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
    fun `closing checkout while the card is being charged lets the charge finish`() {
        val collection = Job()
        ReflectionHelpers.setField(activity, "cardJob", collection)
        setCharging(true)

        call("cancelCardCollection")

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

    private fun showCardIdle(message: String, needsStatusCheck: Boolean) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            activity,
            "showCardIdle",
            ReflectionHelpers.ClassParameter.from(String::class.java, message),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, needsStatusCheck),
        )
    }

    private fun call(method: String, vararg args: String): Any? =
        ReflectionHelpers.callInstanceMethod(
            activity,
            method,
            *args.map { ReflectionHelpers.ClassParameter.from(String::class.java, it) }.toTypedArray(),
        )
}
