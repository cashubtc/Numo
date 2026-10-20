package com.electricdreams.numo.core.dev

import android.app.Application
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ErrorLogMonitorTest {
    @Test
    fun `startup failure is visible and retries successfully without duplicate workers`() = runTest {
        var attempts = 0
        val entries = mutableListOf<ErrorLogEntry>()
        val child = FakeProcess(ByteArrayInputStream(logcatBytes("Recovered")))
        val monitor = ErrorLogMonitor(backgroundScope, 1234, {
            attempts++
            if (attempts == 1) throw IOException("Unavailable")
            child
        }, entries::add)

        monitor.start()
        monitor.start()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(ErrorLogCollectionState.RETRYING, monitor.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals("Recovered", entries.single().message)
        assertTrue(child.destroyed)
        monitor.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(ErrorLogCollectionState.STOPPED, monitor.state.value)
    }

    @Test
    fun `unexpected process exit retries with increasing bounded delay`() = runTest {
        var attempts = 0
        val monitor = ErrorLogMonitor(backgroundScope, 1234, {
            attempts++
            FakeProcess(ByteArrayInputStream(byteArrayOf()))
        }, {})
        monitor.start()
        runCurrent()
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            val previous = attempts
            advanceTimeBy(delay - 1)
            runCurrent()
            assertEquals(previous, attempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(previous + 1, attempts)
        }
        monitor.stop()
    }

    @Test
    fun `stopping destroys the subprocess and unblocks a waiting read`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readStarted = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                closed.await(5, TimeUnit.SECONDS)
                readReleased.countDown()
                return -1
            }

            override fun close() { closed.countDown() }
        }
        val child = FakeProcess(input)
        val monitor = ErrorLogMonitor(scope, 1234, { child }, {})
        try {
            monitor.start()
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            assertEquals(ErrorLogCollectionState.COLLECTING, monitor.state.value)
            monitor.stop()
            assertTrue(child.destroyed)
            assertTrue(readReleased.await(5, TimeUnit.SECONDS))
            assertEquals(ErrorLogCollectionState.STOPPED, monitor.state.value)
        } finally {
            monitor.stop()
            scope.cancel()
        }
    }

    private class FakeProcess(private val input: InputStream) : Process() {
        @Volatile var destroyed = false
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() {
            destroyed = true
            input.close()
        }
    }
}
