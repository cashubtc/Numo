package com.electricdreams.numo.payment.card

import com.electricdreams.numo.payment.card.StripeTerminalManager.FailureReason
import com.electricdreams.numo.payment.card.StripeTerminalManager.RetryStep
import com.stripe.stripeterminal.external.models.PaymentIntentStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StripeTerminalManagerRetryTest {

    @Test
    fun `retry of an already charged intent records it instead of charging again`() {
        assertEquals(RetryStep.ALREADY_PAID, StripeTerminalManager.retryStepFor(PaymentIntentStatus.SUCCEEDED))
    }

    @Test
    fun `retry after a decline collects again on the same intent`() {
        assertEquals(
            RetryStep.COLLECT,
            StripeTerminalManager.retryStepFor(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD),
        )
    }

    @Test
    fun `retry of a collected but unconfirmed intent only confirms it`() {
        assertEquals(
            RetryStep.CONFIRM,
            StripeTerminalManager.retryStepFor(PaymentIntentStatus.REQUIRES_CONFIRMATION),
        )
    }

    @Test
    fun `retry of a cancelled intent starts a new one`() {
        assertEquals(RetryStep.NEW_INTENT, StripeTerminalManager.retryStepFor(PaymentIntentStatus.CANCELED))
    }

    @Test
    fun `retry never charges while the outcome is unclear`() {
        listOf(
            PaymentIntentStatus.PROCESSING,
            PaymentIntentStatus.REQUIRES_CAPTURE,
            PaymentIntentStatus.REQUIRES_ACTION,
            PaymentIntentStatus.REQUIRES_REAUTHORIZATION,
            null,
        ).forEach { status ->
            assertEquals("status=$status", RetryStep.UNRESOLVED, StripeTerminalManager.retryStepFor(status))
        }
    }

    @Test
    fun `confirmation and status retrieval failures require review`() {
        assertEquals(true, StripeTerminalManager.isUnresolvedFailure(true, false))
        assertEquals(true, StripeTerminalManager.isUnresolvedFailure(false, true))
    }

    @Test
    fun `collection failures before confirmation are definitive`() {
        assertEquals(false, StripeTerminalManager.isUnresolvedFailure(false, false))
    }

    @Test
    fun `amount below Stripe's minimum is reported as a setup problem`() {
        assertEquals(
            FailureReason.AMOUNT_TOO_SMALL,
            StripeTerminalManager.failureReasonFor("amount_too_small", "amount"),
        )
    }

    @Test
    fun `currency the account cannot charge is reported as a setup problem`() {
        assertEquals(
            FailureReason.CURRENCY_NOT_SUPPORTED,
            StripeTerminalManager.failureReasonFor(null, "currency"),
        )
        assertEquals(
            FailureReason.CURRENCY_NOT_SUPPORTED,
            StripeTerminalManager.failureReasonFor("payment_method_currency_mismatch", null),
        )
    }

    @Test
    fun `other Stripe errors stay retryable`() {
        assertEquals(FailureReason.OTHER, StripeTerminalManager.failureReasonFor("card_declined", null))
        assertEquals(FailureReason.OTHER, StripeTerminalManager.failureReasonFor(null, null))
    }
}
