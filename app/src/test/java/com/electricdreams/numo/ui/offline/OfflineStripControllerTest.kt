package com.electricdreams.numo.ui.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.network.ConnectionProblem
import com.electricdreams.numo.core.network.MintReachabilityMonitor
import com.electricdreams.numo.ui.offline.OfflineStripView.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineStripControllerTest {

    private val problem = MutableStateFlow<ConnectionProblem?>(null)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val monitor = mock<MintReachabilityMonitor>()
        whenever(monitor.problem).thenReturn(problem)
        ReflectionHelpers.setStaticField(MintReachabilityMonitor::class.java, "instance", monitor)
        resetController()
        OfflineStripController.install(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // Let any pending "tuck away" finish so it can't touch the next test's strip.
        dispatcher.scheduler.advanceUntilIdle()
        resetController()
        ReflectionHelpers.setStaticField(MintReachabilityMonitor::class.java, "instance", null)
        Dispatchers.resetMain()
    }

    @Test
    fun `given nothing is wrong, then there is no strip`() {
        assertNull(OfflineStripController.stripMode.value)
    }

    @Test
    fun `given the phone goes offline and returns, then Back online shows briefly`() {
        problem.value = ConnectionProblem.OFFLINE
        assertEquals(Mode.OFFLINE, OfflineStripController.stripMode.value)

        problem.value = null
        assertEquals(Mode.BACK_ONLINE, OfflineStripController.stripMode.value)

        passHold()
        assertNull(OfflineStripController.stripMode.value)
    }

    @Test
    fun `given the mint stops answering and returns, then Mint connected shows briefly`() {
        problem.value = ConnectionProblem.MINT_UNREACHABLE
        assertEquals(Mode.MINT_UNREACHABLE, OfflineStripController.stripMode.value)

        problem.value = null
        assertEquals(Mode.MINT_RECONNECTED, OfflineStripController.stripMode.value)

        passHold()
        assertNull(OfflineStripController.stripMode.value)
    }

    @Test
    fun `given the mint is unreachable, when the phone drops offline, then offline takes over`() {
        problem.value = ConnectionProblem.MINT_UNREACHABLE
        problem.value = ConnectionProblem.OFFLINE
        assertEquals(Mode.OFFLINE, OfflineStripController.stripMode.value)

        problem.value = null
        assertEquals(Mode.BACK_ONLINE, OfflineStripController.stripMode.value)
    }

    @Test
    fun `given Back online is showing, when the mint turns out unreachable, then the warning stays`() {
        problem.value = ConnectionProblem.OFFLINE
        problem.value = null
        problem.value = ConnectionProblem.MINT_UNREACHABLE

        passHold()

        assertEquals(Mode.MINT_UNREACHABLE, OfflineStripController.stripMode.value)
    }

    private fun passHold() {
        dispatcher.scheduler.advanceTimeBy(2_001L)
        dispatcher.scheduler.runCurrent()
    }

    private fun resetController() {
        ReflectionHelpers.setStaticField(OfflineStripController::class.java, "instance", null)
        ReflectionHelpers.getStaticField<MutableStateFlow<Mode?>>(
            OfflineStripController::class.java, "_stripMode"
        ).value = null
    }
}
