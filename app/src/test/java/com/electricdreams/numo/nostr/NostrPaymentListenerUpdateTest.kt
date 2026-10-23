package com.electricdreams.numo.nostr

import android.app.Application
import com.electricdreams.numo.core.update.UpdateOperationGate
import com.electricdreams.numo.ndef.CashuPaymentHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NostrPaymentListenerUpdateTest {
    private val secret = ByteArray(32)
    private val event = NostrEvent().apply {
        kind = 1059
        id = "payment-event"
    }

    @Test
    fun `relay processing and failure callback block installation after screen closes`() {
        var callbackCalled = false
        val listener = listener { _, _ ->
            callbackCalled = true
            assertFalse(UpdateOperationGate.shared.tryBeginInstall())
        }
        mockStatic(Nip59::class.java).use { nip59 ->
            nip59.`when`<Nip59.UnwrappedDm> {
                Nip59.unwrapGiftWrappedDm(event, secret)
            }.thenAnswer {
                assertFalse(UpdateOperationGate.shared.tryBeginInstall())
                throw IllegalArgumentException("Invalid giftwrap")
            }
            handleEvent(listener)
            assertTrue(callbackCalled)
            assertTrue(UpdateOperationGate.shared.tryBeginInstall())
            UpdateOperationGate.shared.finishInstall()
        }
    }

    @Test
    fun `event received during installation is not processed or marked as seen`() {
        val listener = listener { _, _ -> }
        mockStatic(Nip59::class.java).use { nip59 ->
            nip59.`when`<Nip59.UnwrappedDm> {
                Nip59.unwrapGiftWrappedDm(event, secret)
            }.thenThrow(IllegalArgumentException("Invalid giftwrap"))
            assertTrue(UpdateOperationGate.shared.tryBeginInstall())
            try {
                handleEvent(listener)
                nip59.verifyNoInteractions()
            } finally {
                UpdateOperationGate.shared.finishInstall()
            }
            handleEvent(listener)
            nip59.verify { Nip59.unwrapGiftWrappedDm(event, secret) }
            assertTrue(UpdateOperationGate.shared.tryBeginInstall())
            UpdateOperationGate.shared.finishInstall()
        }
    }

    private fun listener(onFailure: NostrPaymentListener.ErrorHandler) = NostrPaymentListener(
        secret, "pubkey", 10L, emptyList(), emptyList(), {}, onFailure, "sat",
    )

    @Test
    fun `pending swap is delivered without a payment failure`() {
        assertPendingCallback(wrapped = false)
    }

    @Test
    fun `wrapped pending swap is delivered without a payment failure`() {
        assertPendingCallback(wrapped = true)
    }

    private fun assertPendingCallback(wrapped: Boolean) {
        var pendingId: String? = null
        var failed = false
        val listener = NostrPaymentListener(secret, "pubkey", 10L, emptyList(), emptyList(), {},
            object : NostrPaymentListener.ErrorHandler {
                override fun onPaymentFailure(message: String?, t: Throwable?) { failed = true }
                override fun onPaymentPending(paymentId: String) { pendingId = paymentId }
            }, "sat", "pos-payment")
        mockStatic(Nip59::class.java).use { nip59 ->
            nip59.`when`<Nip59.UnwrappedDm> {
                Nip59.unwrapGiftWrappedDm(event, secret)
            }.thenAnswer {
                val pending = CashuPaymentHelper.PendingSwapException("pos-payment")
                if (wrapped) throw RuntimeException(pending) else throw pending
            }
            handleEvent(listener)
            assertEquals("pos-payment", pendingId)
            assertFalse(failed)
            handleEvent(listener)
            nip59.verify { Nip59.unwrapGiftWrappedDm(event, secret) }
        }
    }

    private fun handleEvent(listener: NostrPaymentListener) {
        NostrPaymentListener::class.java.getDeclaredMethod(
            "handleEvent", String::class.java, NostrEvent::class.java,
        ).apply { isAccessible = true }.invoke(listener, "wss://relay.example", event)
    }
}
