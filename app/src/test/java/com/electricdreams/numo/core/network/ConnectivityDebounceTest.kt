package com.electricdreams.numo.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityDebounceTest {

    private val delayMs = ConnectivityMonitor.OFFLINE_DEBOUNCE_MS

    @Test
    fun `given online, when network drops for the full debounce, then offline is emitted`() =
        runTest {
            val raw = MutableStateFlow(true)
            val emitted = mutableListOf<Boolean>()
            backgroundScope.launch { raw.debounceOffline(delayMs).collect { emitted += it } }
            runCurrent()

            raw.value = false
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(listOf(true), emitted)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(true, false), emitted)
        }

    @Test
    fun `given online, when network blips shorter than the debounce, then nothing changes`() =
        runTest {
            val raw = MutableStateFlow(true)
            val emitted = mutableListOf<Boolean>()
            backgroundScope.launch { raw.debounceOffline(delayMs).collect { emitted += it } }
            runCurrent()

            raw.value = false
            advanceTimeBy(delayMs / 2)
            raw.value = true
            advanceTimeBy(delayMs * 2)
            runCurrent()

            assertEquals(listOf(true), emitted)
        }

    @Test
    fun `given offline, when network returns, then online is emitted immediately`() = runTest {
        val raw = MutableStateFlow(false)
        val emitted = mutableListOf<Boolean>()
        backgroundScope.launch { raw.debounceOffline(delayMs).collect { emitted += it } }
        advanceTimeBy(delayMs)
        runCurrent()
        assertEquals(listOf(false), emitted)

        raw.value = true
        runCurrent()

        assertEquals(listOf(false, true), emitted)
    }
}
