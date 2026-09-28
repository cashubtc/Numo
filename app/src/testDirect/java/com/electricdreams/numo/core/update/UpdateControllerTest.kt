package com.electricdreams.numo.core.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository: UpdateRepository = mock()
    private val installer: PackageInstaller = mock()
    private val session: PackageInstaller.Session = mock()
    private val update = UpdateManifest(26, "1.10", 24,
        "https://github.com/cashubtc/Numo/releases/download/v1.10/numo-v1.10-universal.apk",
        1, "a".repeat(64), "Notes")
    private val gate = UpdateOperationGate()

    @Before fun setUp() {
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit()
        val apk = temporary.newFile("app.apk").apply { writeText("APK") }
        whenever(repository.cached()).thenReturn(update)
        whenever(repository.readyFile(update)).thenReturn(apk)
        whenever(repository.verifyForInstall(update)).thenReturn(apk)
        whenever(installer.createSession(any())).thenReturn(42)
        whenever(installer.openSession(42)).thenReturn(session)
        whenever(session.openWrite(any(), any(), any())).thenReturn(ByteArrayOutputStream())
    }

    @Test fun `active withdrawal prevents installation without touching the installer`() = runTest {
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        val operation = requireNotNull(gate.tryBeginOperation())
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            controller.install(activity.get())
            advanceUntilIdle()
            assertEquals(R.string.update_payment_busy, controller.state.value.message)
            verify(installer, never()).createSession(any())
        }
        operation.close()
    }

    @Test fun `installation holds the gate through confirmation and cancellation releases it`() = runTest {
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            controller.install(activity.get())
            advanceUntilIdle()
            verify(repository).verifyForInstall(update)
            verify(session).commit(any())
            assertFalse(gate.tryBeginInstall())
            controller.onInstallStatus(Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, Intent("android.intent.action.VIEW")))
            assertNotNull(controller.takeConfirmation())
            controller.onInstallerReturned(Activity.RESULT_CANCELED)
            verify(installer).abandonSession(42)
            assertEquals(UpdatePhase.READY, controller.state.value.phase)
            assertTrue(gate.tryBeginInstall())
        }
    }

    @Test fun `verification failure never submits an APK and releases the install gate`() = runTest {
        whenever(repository.verifyForInstall(update)).thenThrow(IllegalArgumentException("Wrong signer"))
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            controller.install(activity.get())
            advanceUntilIdle()
            verify(installer, never()).createSession(any())
            assertEquals(R.string.update_install_failed, controller.state.value.message)
            assertTrue(gate.tryBeginInstall())
        }
    }

    @Test fun `network failure preserves a downloaded update for offline installation`() = runTest {
        whenever(repository.check()).thenThrow(IllegalStateException("offline"))
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        controller.check(force = true)
        advanceUntilIdle()
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
        assertEquals(R.string.update_check_failed, controller.state.value.message)
    }

    @Test fun `process recreation abandons a stale session before accepting payments`() = runTest {
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            .edit().putInt("session_id", 41).commit()
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        verify(installer).abandonSession(41)
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
        assertTrue(gate.tryBeginInstall())
    }

    @Test fun `a callback from an old session cannot release the current installation`() = runTest {
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            controller.install(activity.get())
            advanceUntilIdle()
            controller.onInstallStatus(Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, 12)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
            assertEquals(UpdatePhase.INSTALLING, controller.state.value.phase)
            assertFalse(gate.tryBeginInstall())
            controller.cancelInstall()
        }
    }

    @Test fun `cancel during preparation waits until the installer session can be abandoned`() = runTest {
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            controller.install(activity.get())
            controller.cancelInstall()
            assertFalse(gate.tryBeginInstall())
            advanceUntilIdle()
            verify(installer).abandonSession(42)
            assertEquals(UpdatePhase.READY, controller.state.value.phase)
            assertTrue(gate.tryBeginInstall())
        }
    }

    @Test fun `failed cancellation retains the gate until Android reports a terminal result`() = runTest {
        whenever(installer.getSessionInfo(42)).thenReturn(mock())
        org.mockito.kotlin.doThrow(SecurityException("still installing"))
            .whenever(installer).abandonSession(42)
        val controller = UpdateController(context, { repository }, this,
            StandardTestDispatcher(testScheduler), installer, gate)
        advanceUntilIdle()
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            controller.install(activity.get())
            advanceUntilIdle()
            controller.cancelInstall()
            assertFalse(gate.tryBeginInstall())
            controller.onInstallStatus(Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
            assertTrue(gate.tryBeginInstall())
        }
    }
}
