package com.electricdreams.numo.core.network

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MintReachabilityMonitorTest {

    private val online = MutableStateFlow(true)
    private var mint: String? = "https://mint.example"
    private var answers = true
    private val probed = mutableListOf<String>()

    private fun TestScope.monitor(foreground: Boolean = true): MintReachabilityMonitor =
        MintReachabilityMonitor(
            isOnline = online,
            mintUrl = { mint },
            probe = { url ->
                probed += url
                answers
            },
            scope = backgroundScope,
        ).also {
            it.setForeground(foreground)
            runCurrent()
        }

    private fun TestScope.passRetry() {
        advanceTimeBy(MintReachabilityMonitor.RETRY_INTERVAL_MS)
        runCurrent()
    }

    @Test
    fun `given the mint answers, then there is no problem and it is checked once a minute`() =
        runTest {
            val monitor = monitor()

            assertEquals(1, probed.size)
            assertNull(monitor.problem.value)

            advanceTimeBy(MintReachabilityMonitor.HEALTHY_INTERVAL_MS)
            runCurrent()
            assertEquals(2, probed.size)
        }

    @Test
    fun `given one failed check, then the mint still counts as reachable`() = runTest {
        answers = false
        val monitor = monitor()

        assertEquals(1, probed.size)
        assertTrue(monitor.isMintReachable.value)
        assertNull(monitor.problem.value)
    }

    @Test
    fun `given two failed checks in a row, then the mint is unreachable`() = runTest {
        answers = false
        val monitor = monitor()

        passRetry()

        assertFalse(monitor.isMintReachable.value)
        assertEquals(ConnectionProblem.MINT_UNREACHABLE, monitor.problem.value)
    }

    @Test
    fun `given an unreachable mint, when it answers once, then the problem clears`() = runTest {
        answers = false
        val monitor = monitor()
        passRetry()

        answers = true
        advanceTimeBy(MintReachabilityMonitor.UNREACHABLE_INTERVAL_MS)
        runCurrent()

        assertNull(monitor.problem.value)
    }

    @Test
    fun `given a failure between two answers, then the count starts over`() = runTest {
        answers = false
        val monitor = monitor()
        answers = true
        passRetry()

        answers = false
        advanceTimeBy(MintReachabilityMonitor.HEALTHY_INTERVAL_MS)
        runCurrent()

        assertTrue(monitor.isMintReachable.value)
    }

    @Test
    fun `given the phone goes offline, then offline wins and probing stops`() = runTest {
        answers = false
        val monitor = monitor()
        passRetry()

        online.value = false
        runCurrent()
        val before = probed.size
        advanceTimeBy(MintReachabilityMonitor.HEALTHY_INTERVAL_MS)
        runCurrent()

        assertEquals(ConnectionProblem.OFFLINE, monitor.problem.value)
        assertEquals(before, probed.size)
    }

    @Test
    fun `given an unreachable mint, when the phone reconnects, then it starts clean and rechecks`() =
        runTest {
            answers = false
            val monitor = monitor()
            passRetry()
            online.value = false
            runCurrent()
            val before = probed.size

            answers = true
            online.value = true
            runCurrent()

            assertNull(monitor.problem.value)
            assertEquals(before + 1, probed.size)
        }

    @Test
    fun `given the app is in the background, then the mint is not probed`() = runTest {
        monitor(foreground = false)

        advanceTimeBy(MintReachabilityMonitor.HEALTHY_INTERVAL_MS)
        runCurrent()

        assertTrue(probed.isEmpty())
    }

    @Test
    fun `given the app returns to the foreground, then the mint is checked right away`() =
        runTest {
            val monitor = monitor(foreground = false)

            monitor.setForeground(true)
            runCurrent()

            assertEquals(1, probed.size)
        }

    @Test
    fun `given an unreachable mint, when the app is backgrounded, then the warning is kept`() =
        runTest {
            answers = false
            val monitor = monitor()
            passRetry()

            monitor.setForeground(false)
            runCurrent()

            assertEquals(ConnectionProblem.MINT_UNREACHABLE, monitor.problem.value)
        }

    @Test
    fun `given a reported failure, then the mint is checked without waiting for the next tick`() =
        runTest {
            val monitor = monitor()
            answers = false

            monitor.reportFailure()
            runCurrent()

            assertEquals(2, probed.size)
            assertTrue(monitor.isMintReachable.value)
            passRetry()
            assertFalse(monitor.isMintReachable.value)
        }

    @Test
    fun `given the merchant switches mint, then the new mint starts clean`() = runTest {
        answers = false
        val monitor = monitor()
        passRetry()

        mint = "https://other.example"
        answers = true
        advanceTimeBy(MintReachabilityMonitor.UNREACHABLE_INTERVAL_MS)
        runCurrent()

        assertNull(monitor.problem.value)
        assertEquals("https://other.example", probed.last())
    }

    @Test
    fun `given nothing to watch, then nothing is probed and nothing is wrong`() = runTest {
        mint = null
        answers = false
        val monitor = monitor()

        advanceTimeBy(MintReachabilityMonitor.HEALTHY_INTERVAL_MS)
        runCurrent()

        assertTrue(probed.isEmpty())
        assertNull(monitor.problem.value)
    }

    @Test
    fun `given the app is on screen, then rotating between two activities keeps it probing`() =
        runTest {
            val monitor = monitor(foreground = false)
            val tracker = MintReachabilityMonitor.ForegroundTracker(monitor)
            val first = mock<Activity>()
            val second = mock<Activity>()

            tracker.onActivityStarted(first)
            tracker.onActivityStarted(second)
            tracker.onActivityStopped(first)
            runCurrent()
            assertEquals(1, probed.size)

            tracker.onActivityStopped(second)
            runCurrent()
            advanceTimeBy(MintReachabilityMonitor.HEALTHY_INTERVAL_MS)
            runCurrent()
            assertEquals(1, probed.size)
        }

    @Test
    fun `the mint that issues new charges is the one watched`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceStore.app(context).putBoolean("btcpay_enabled", false)

        assertEquals(
            MintManager.getInstance(context).getPreferredLightningMint(),
            MintReachabilityMonitor.watchedMint(context)
        )
    }

    @Test
    fun `given BTCPay handles checkout, then no mint is watched`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceStore.app(context)
        prefs.putBoolean("btcpay_enabled", true)
        prefs.putString("btcpay_server_url", "https://btcpay.example")
        prefs.putString("btcpay_api_key", "key")

        // Incomplete BTCPay setup falls back to the local wallet, so the mint still matters.
        assertEquals(
            MintManager.getInstance(context).getPreferredLightningMint(),
            MintReachabilityMonitor.watchedMint(context)
        )

        prefs.putString("btcpay_store_id", "store")
        assertNull(MintReachabilityMonitor.watchedMint(context))

        // The preference store outlives a test, so leave it as the other tests expect.
        prefs.putBoolean("btcpay_enabled", false)
    }

    @Test
    fun `mint info answering with JSON counts as reachable`() {
        assertTrue(probe(MockResponse().setBody("""{"name":"Test mint"}""")))
    }

    @Test
    fun `a sign-in page answering 200 with HTML does not count as the mint`() {
        assertFalse(probe(MockResponse().setBody("<html><body>Sign in to Wi-Fi</body></html>")))
    }

    @Test
    fun `a server error does not count as reachable`() {
        assertFalse(probe(MockResponse().setResponseCode(503).setBody("{}")))
    }

    @Test
    fun `a dropped connection does not count as reachable`() {
        assertFalse(probe(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)))
    }

    @Test
    fun `a mint that never answers times out as unreachable`() {
        assertFalse(probe(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)))
    }

    private fun probe(response: MockResponse): Boolean = MockWebServer().use { server ->
        server.enqueue(response)
        server.start()
        val client = OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build()
        val answered = runBlocking {
            MintReachabilityMonitor.mintAnswers(client, server.url("/Bitcoin/").toString())
        }
        if (response.socketPolicy == SocketPolicy.KEEP_OPEN) {
            assertEquals("/Bitcoin/v1/info", server.takeRequest().path)
        }
        answered
    }
}
