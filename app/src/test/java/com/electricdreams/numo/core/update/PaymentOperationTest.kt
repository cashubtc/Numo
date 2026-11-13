package com.electricdreams.numo.core.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PaymentOperationTest {
    @Test fun `queued payment work blocks installation before the dispatcher starts it`() = runTest {
        val payment = launchPaymentOperation { }
        assertFalse(UpdateOperationGate.shared.tryBeginInstall())
        payment.join()
        assertTrue(UpdateOperationGate.shared.tryBeginInstall())
        UpdateOperationGate.shared.finishInstall()
    }

    @Test fun `already cancelled parent does not leak an operation lease`() = runTest {
        val parent = Job().apply { cancel() }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        scope.launchPaymentOperation { error("Must not run") }.join()
        assertTrue(UpdateOperationGate.shared.tryBeginInstall())
        UpdateOperationGate.shared.finishInstall()
    }

    @Test fun `new payment waits for installation cancellation before accessing the wallet`() = runTest {
        assertTrue(UpdateOperationGate.shared.tryBeginInstall())
        val entered = CompletableDeferred<Unit>()
        val payment = launchPaymentOperation { entered.complete(Unit) }
        runCurrent()
        assertFalse(entered.isCompleted)
        UpdateOperationGate.shared.finishInstall()
        payment.join()
        assertTrue(entered.isCompleted)
    }
}
