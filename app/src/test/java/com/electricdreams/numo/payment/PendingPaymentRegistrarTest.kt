package com.electricdreams.numo.payment

import android.content.Context
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
class PendingPaymentRegistrarTest {

    private lateinit var context: Context
    private lateinit var bitcoinPriceWorker: BitcoinPriceWorker

    @Before
    fun setup() {
        context = mock()
        bitcoinPriceWorker = mock()
    }

    @Test
    fun `registerPendingPayment stores parsed entry unit`() {
        val fakeStore = FakeStore()
        val registrar = PendingPaymentRegistrar(context, bitcoinPriceWorker, fakeStore)
        whenever(bitcoinPriceWorker.getCurrentPrice()).thenReturn(50_000.0)

        val result = registrar.registerPendingPayment(
            paymentAmount = 1_000L,
            formattedAmountString = "₿1,000",
            tipAmountSats = 0L,
            tipPercentage = 0,
            baseAmountSats = 0L,
            baseFormattedAmount = null,
            checkoutBasketJson = null,
            savedBasketId = null,
        )

        assertEquals("pending-fake", result)
        fakeStore.assertLastEntryUnit("sat")
    }
}


    private class FakeStore : PendingPaymentStore {
        var lastEntryUnit: String? = null
        override fun addPendingPayment(
            context: Context,
            amount: Long,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            paymentRequest: String?,
            formattedAmount: String,
            checkoutBasketJson: String?,
            basketId: String?,
            tipAmountSats: Long,
            tipPercentage: Int,
        ): String? {
            lastEntryUnit = entryUnit
            return "pending-fake"
        }

        fun assertLastEntryUnit(expected: String) {
            if (lastEntryUnit != expected) {
                throw AssertionError("Expected entry unit $expected but was $lastEntryUnit")
            }
        }
    }
