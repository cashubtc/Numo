package com.electricdreams.numo

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
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
