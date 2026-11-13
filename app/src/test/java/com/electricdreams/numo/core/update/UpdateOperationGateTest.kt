package com.electricdreams.numo.core.update

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateOperationGateTest {
    @Test
    fun `an active operation prevents installation until its last lease closes`() {
        val gate = UpdateOperationGate()
        val first = requireNotNull(gate.tryBeginOperation())
        val second = requireNotNull(gate.tryBeginOperation())
        assertFalse(gate.tryBeginInstall())
        first.close()
        first.close()
        assertFalse(gate.tryBeginInstall())
        second.close()
        assertTrue(gate.tryBeginInstall())
        assertNull(gate.tryBeginOperation())
        assertFalse(gate.tryBeginInstall())
    }

    @Test
    fun `cancelling installation releases waiting operations`() = runTest {
        val gate = UpdateOperationGate()
        assertTrue(gate.tryBeginInstall())
        var ran = false
        val operation = async { gate.withOperation { ran = true } }
        runCurrent()
        assertFalse(ran)
        gate.finishInstall()
        operation.await()
        assertTrue(ran)
        assertNotNull(gate.tryBeginOperation())
    }

    @Test
    fun `operation exceptions release the lease and nested work does not deadlock`() = runTest {
        val gate = UpdateOperationGate()
        runCatching {
            gate.withOperation {
                gate.withOperation { assertFalse(gate.tryBeginInstall()) }
                error("failed payment")
            }
        }
        assertTrue(gate.tryBeginInstall())
    }
}
