package com.electricdreams.numo

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.ndef.NdefHostCardEmulationService
import com.electricdreams.numo.payment.LightningMintHandler
import com.electricdreams.numo.payment.MintQuoteWebSocket
import com.electricdreams.numo.payment.NostrPaymentHandler
import com.electricdreams.numo.payment.PaymentTabManager
import com.electricdreams.numo.ui.animation.NfcPaymentAnimationView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
    ) {
        val mintManager = mock<MintManager>()
        whenever(mintManager.getPreferredUnit()).thenReturn(unit)
        whenever(mintManager.getAllowedMints()).thenReturn(emptyList())
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", mintManager)
        ReflectionHelpers.setField(activity, "paymentAmount", amount)
        ReflectionHelpers.setField(activity, "formattedAmountString", formatted)
        val amountDisplay = TextView(activity).apply { text = formatted }
        val convertedDisplay = TextView(activity).apply { visibility = View.VISIBLE }
        ReflectionHelpers.setField(activity, "largeAmountDisplay", amountDisplay)
        ReflectionHelpers.setField(activity, "convertedAmountDisplay", convertedDisplay)
        ReflectionHelpers.setField(activity, "tabManager", mock<PaymentTabManager>())

        // Exercise checkout initialization while keeping payment services offline.
        mockConstruction(LightningMintHandler::class.java).use {
            mockConstruction(NostrPaymentHandler::class.java).use {
                mockStatic(NdefHostCardEmulationService::class.java).use {
                    ReflectionHelpers.callInstanceMethod<Void>(activity, "initializeLocalPaymentRequest")
                }
            }
        }

        assertEquals(expected, amountDisplay.text.toString())
        assertEquals(View.GONE, convertedDisplay.visibility)
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
