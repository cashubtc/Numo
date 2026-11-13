package com.electricdreams.numo.core.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectivityMonitorTest {

    private lateinit var context: Context
    private lateinit var manager: ConnectivityManager
    private lateinit var network: Network
    private lateinit var capabilities: NetworkCapabilities

    @Before
    fun setUp() {
        context = mock()
        manager = mock()
        network = mock()
        capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager)
        whenever(manager.getNetworkCapabilities(network)).thenReturn(capabilities)
    }

    @Test
    @Config(sdk = [24, 25])
    fun `Android 7 reconnects from availability alone after a disconnect`() = runTest {
        whenever(manager.activeNetwork).thenReturn(network)
        val monitor = ConnectivityMonitor(context, backgroundScope)
        val callback = registeredCallback()
        runCurrent()

        whenever(manager.activeNetwork).thenReturn(null)
        callback.onLost(network)
        runCurrent()
        advanceTimeBy(ConnectivityMonitor.OFFLINE_DEBOUNCE_MS)
        runCurrent()
        assertFalse(monitor.isOnline.value)
        assertFalse(monitor.isOnlineNow())

        whenever(manager.activeNetwork).thenReturn(network)
        callback.onAvailable(network)
        runCurrent()

        assertTrue(monitor.isOnline.value)
        assertTrue(monitor.isOnlineNow())
    }

    @Test
    @Config(sdk = [24, 25])
    fun `Android 7 availability does not treat a captive portal as online`() = runTest {
        val monitor = ConnectivityMonitor(context, backgroundScope)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        whenever(manager.activeNetwork).thenReturn(network)

        registeredCallback().onAvailable(network)
        runCurrent()

        assertFalse(monitor.isOnline.value)
        assertFalse(monitor.isOnlineNow())
    }

    @Test
    @Config(sdk = [26, 34])
    fun `Android 8 and newer wait for capabilities after availability`() = runTest {
        val monitor = ConnectivityMonitor(context, backgroundScope)
        val callback = registeredCallback()
        whenever(manager.activeNetwork).thenReturn(network)

        callback.onAvailable(network)
        runCurrent()
        assertFalse(monitor.isOnline.value)
        verify(manager, times(1)).activeNetwork

        callback.onCapabilitiesChanged(network, capabilities)
        runCurrent()
        assertTrue(monitor.isOnline.value)
    }

    @Test
    fun `failed registration keeps the shared flow current across disconnect and reconnect`() =
        runTest {
            whenever(manager.activeNetwork).thenReturn(network)
            failRegistration()
            val monitor = ConnectivityMonitor(context, backgroundScope)
            val emitted = mutableListOf<Boolean>()
            backgroundScope.launch { monitor.isOnline.collect { emitted += it } }
            runCurrent()

            whenever(manager.activeNetwork).thenReturn(null)
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(monitor.isOnline.value)
            advanceTimeBy(ConnectivityMonitor.OFFLINE_DEBOUNCE_MS)
            runCurrent()
            assertEquals(listOf(true, false), emitted)

            whenever(manager.activeNetwork).thenReturn(network)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf(true, false, true), emitted)
        }

    @Test
    fun `failed registration starting offline updates the shared flow after reconnect`() = runTest {
        failRegistration()
        val monitor = ConnectivityMonitor(context, backgroundScope)
        runCurrent()
        assertFalse(monitor.isOnline.value)

        whenever(manager.activeNetwork).thenReturn(network)
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(monitor.isOnline.value)
        assertTrue(monitor.isOnlineNow())
    }

    private fun registeredCallback(): ConnectivityManager.NetworkCallback {
        val captor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        verify(manager).registerDefaultNetworkCallback(captor.capture())
        return captor.firstValue
    }

    private fun failRegistration() {
        doThrow(IllegalStateException("Callback limit reached"))
            .whenever(manager).registerDefaultNetworkCallback(any())
    }
}
