package com.electricdreams.numo

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.ndef.NdefHostCardEmulationService
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PaymentRequestActivityNfcFlowTest {

    private lateinit var activity: PaymentRequestActivity
    private lateinit var overlay: FrameLayout
    private lateinit var animationView: NfcPaymentAnimationView
    private lateinit var waitingHint: TextView
    private lateinit var resultAmount: TextView
    private lateinit var resultLabel: TextView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(PaymentRequestActivity::class.java).get()
        activity.window.setContentView(FrameLayout(activity).apply { id = R.id.payment_request_root })
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", mock<AutoWithdrawManager>())

        overlay = FrameLayout(activity)
        animationView = mock()
        waitingHint = TextView(activity)
        resultAmount = TextView(activity)
        resultLabel = TextView(activity)
        val tabs = mock<PaymentTabManager>()
        whenever(tabs.getCurrentTab()).thenReturn(PaymentTabManager.PaymentTab.UNIFIED)

        mapOf(
            "tabManager" to tabs,
            "paymentAmount" to 1_000L,
            "formattedAmountString" to "$1.00",
            "statusText" to TextView(activity),
            "nfcAnimationContainer" to overlay,
            "nfcAnimationView" to animationView,
            "animationWaitingHintText" to waitingHint,
            "animationResultAmountText" to resultAmount,
            "animationResultLabelText" to resultLabel,
            "animationActionsContainer" to View(activity),
            "animationViewDetailsButton" to TextView(activity),
            "animationCloseButton" to TextView(activity),
        ).forEach { (field, value) -> ReflectionHelpers.setField(activity, field, value) }
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(NdefHostCardEmulationService::class.java, "instance", null)
        ReflectionHelpers.setStaticField(NdefHostCardEmulationService::class.java, "pendingReadyListener", null)
    }

    @Test
    fun `trailing HCE timeout during active processing never exposes QR screen`() {
        overlay.visibility = View.VISIBLE
        ReflectionHelpers.setField(activity, "isProcessingNfcPayment", true)

        ReflectionHelpers.callInstanceMethod<Any?>(
            activity,
            "handleNfcReadingStopped",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(ReflectionHelpers.getField(activity, "isProcessingNfcPayment"))
        verify(animationView, never()).reset()
    }

    @Test
    fun `mid-write tap loss shows recoverable retry hint without killing checkout`() {
        overlay.visibility = View.VISIBLE
        waitingHint.visibility = View.VISIBLE
        waitingHint.text = activity.getString(R.string.nfc_payment_hint_keep_close)

        ReflectionHelpers.callInstanceMethod<Any?>(
            activity,
            "handleNfcReadingStopped",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        // Advance past the 90ms+160ms hint swap animation, staying well within the 12s non-terminal timeout.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertFalse("Mid-tap drop must keep checkout alive for an immediate re-tap", ReflectionHelpers.getField(activity, "hasTerminalOutcome"))
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(activity.getString(R.string.nfc_payment_hint_tap_lost), waitingHint.text.toString())
        verify(animationView, never()).showError(any())
        verify(animationView, never()).showErrorDirect(any())

        // If the customer doesn't re-tap, the safety timer fades gracefully back to the live QR.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertFalse(ReflectionHelpers.getField(activity, "hasTerminalOutcome"))
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun `processing reveals overlay directly even if waiting overlay had closed`() {
        overlay.visibility = View.GONE

        ReflectionHelpers.callInstanceMethod<Any?>(activity, "showNfcAnimationProcessing")

        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(activity.getString(R.string.nfc_payment_hint_processing), waitingHint.text.toString())
        verify(animationView).startProcessingDirectly(animateIn = true)
    }

    @Test
    fun `non-NFC payment success uses direct entrance without NFC waves`() {
        overlay.visibility = View.GONE

        PaymentRequestActivity::class.java.getDeclaredMethod(
            "showPaymentSuccess",
            String::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(activity, "", 1_000L, null, false)

        assertEquals(View.VISIBLE, overlay.visibility)
        verify(animationView).showSuccessDirect(eq("$1.00"))
        verify(animationView, never()).showSuccess(any())
    }

    @Test
    fun `active tap success morphs from tap screen instead of direct entrance`() {
        overlay.visibility = View.VISIBLE
        ReflectionHelpers.setField(activity, "isCardSurfaceCovered", false)

        PaymentRequestActivity::class.java.getDeclaredMethod(
            "showPaymentSuccess",
            String::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(activity, "", 1_000L, null, false)

        verify(animationView).showSuccess(eq("$1.00"))
        verify(animationView, never()).showSuccessDirect(any())
    }

    @Test
    fun `HCE runWhenReady invokes immediately when service is already running`() {
        val fakeService = mock<NdefHostCardEmulationService>()
        ReflectionHelpers.setStaticField(NdefHostCardEmulationService::class.java, "instance", fakeService)
        var received: NdefHostCardEmulationService? = null

        NdefHostCardEmulationService.runWhenReady { service -> received = service }

        assertEquals(fakeService, received)
    }
}
