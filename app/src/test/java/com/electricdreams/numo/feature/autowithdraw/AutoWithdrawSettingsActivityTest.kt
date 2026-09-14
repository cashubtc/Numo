package com.electricdreams.numo.feature.autowithdraw

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.util.LnUrlClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoWithdrawSettingsActivityTest {
    private lateinit var settings: AutoWithdrawSettingsManager
    private val client: LnUrlClient = mock()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppGlobals.init(context)
        ReflectionHelpers.setStaticField(LightningAddressManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(AutoWithdrawSettingsManager::class.java, "instance", null)
        ReflectionHelpers.setStaticField(AutoWithdrawManager::class.java, "instance", null)
        settings = AutoWithdrawSettingsManager.getInstance(context)
        settings.setGloballyEnabled(true)
        settings.setDefaultLightningAddress("")
        settings.setDefaultThreshold(AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS)
    }

    @Test
    fun `saved malformed address opens repeatedly and can be corrected`() {
        settings.setDefaultLightningAddress("user@.com")
        repeat(2) {
            activityController().setup().use { controller ->
                val activity = controller.get()
                assertEquals("user@.com", input(activity).text.toString())
                assertInvalid(activity)
                verifyNoInteractions(client)
            }
        }

        whenever(client.fetchLnUrlDetails("user@example.com")).thenReturn(details())
        activityController().setup().use { controller ->
            val activity = controller.get()
            input(activity).setText("user@example.com")
            await { lookupJob(activity).isCompleted }
            assertEquals("user@example.com", settings.getDefaultLightningAddress())
            assertEquals(
                activity.getString(R.string.auto_withdraw_lightning_address_valid),
                validation(activity).text.toString(),
            )
        }
    }

    @Test
    fun `deleting host while lookup is pending keeps invalid state and saved threshold`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        whenever(client.fetchLnUrlDetails("user@x.com")).thenAnswer {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            details()
        }
        try {
            activityController().setup().use { controller ->
                val activity = controller.get()
                input(activity).setText("user@x.com")
                val pending = lookupJob(activity)
                await { started.count == 0L }
                input(activity).text.delete(5, 6) // user@x.com -> user@.com
                assertInvalid(activity)
                release.countDown()
                await { pending.isCompleted }
                assertTrue(pending.isCancelled)
                assertInvalid(activity)
                assertEquals("user@.com", settings.getDefaultLightningAddress())
                assertEquals(
                    AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS,
                    settings.getDefaultThreshold(),
                )
                verify(client).fetchLnUrlDetails("user@x.com")
                verifyNoMoreInteractions(client)
                input(activity).setText("")
                assertEquals(View.GONE, validation(activity).visibility)
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `unexpected lookup failure shows invalid state instead of crashing`() {
        whenever(client.fetchLnUrlDetails("user@example.com"))
            .thenThrow(IllegalArgumentException("Unexpected lookup failure"))
        settings.setDefaultLightningAddress("user@example.com")
        activityController().setup().use { controller ->
            val activity = controller.get()
            await { lookupJob(activity).isCompleted }
            assertInvalid(activity)
            assertEquals(
                AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS,
                settings.getDefaultThreshold(),
            )
        }
    }

    @Test
    fun `failure from a cancelled lookup cannot overwrite a newer valid address`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        whenever(client.fetchLnUrlDetails("user@old.example.com")).thenAnswer {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("Late failure")
        }
        whenever(client.fetchLnUrlDetails("user@new.example.com")).thenReturn(details())
        try {
            activityController().setup().use { controller ->
                val activity = controller.get()
                input(activity).setText("user@old.example.com")
                val oldLookup = lookupJob(activity)
                await { started.count == 0L }
                input(activity).setText("user@new.example.com")
                await { lookupJob(activity).isCompleted }
                release.countDown()
                await { oldLookup.isCompleted }
                assertEquals(
                    activity.getString(R.string.auto_withdraw_lightning_address_valid),
                    validation(activity).text.toString(),
                )
                assertEquals("user@new.example.com", settings.getDefaultLightningAddress())
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `destroying the screen cancels pending threshold updates`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        whenever(client.fetchLnUrlDetails("user@example.com")).thenAnswer {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            details()
        }
        try {
            val pending = activityController().setup().use { controller ->
                input(controller.get()).setText("user@example.com")
                await { started.count == 0L }
                lookupJob(controller.get())
            }
            release.countDown()
            await { pending.isCompleted }
            assertTrue(pending.isCancelled)
            assertEquals(
                AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS,
                settings.getDefaultThreshold(),
            )
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `successful lookup still updates the minimum withdrawal threshold`() {
        whenever(client.fetchLnUrlDetails("user@example.com")).thenReturn(details())
        settings.setDefaultLightningAddress("user@example.com")
        activityController().setup().use { controller ->
            val activity = controller.get()
            await { lookupJob(activity).isCompleted }
            assertEquals(
                activity.getString(R.string.auto_withdraw_lightning_address_valid),
                validation(activity).text.toString(),
            )
            assertEquals(63_158L, settings.getDefaultThreshold())
        }
    }

    @Test
    fun `lookup cancellation stays cancellation instead of rendering a failure`() {
        whenever(client.fetchLnUrlDetails("user@example.com"))
            .thenThrow(CancellationException("Screen closed"))
        settings.setDefaultLightningAddress("user@example.com")
        activityController().setup().use { controller ->
            val activity = controller.get()
            await { lookupJob(activity).isCompleted }
            assertTrue(lookupJob(activity).isCancelled)
            assertEquals(
                activity.getString(R.string.auto_withdraw_lightning_address_checking),
                validation(activity).text.toString(),
            )
        }
    }

    private fun activityController() =
        Robolectric.buildActivity(AutoWithdrawSettingsActivity::class.java).also {
            ReflectionHelpers.setField(it.get(), "lnUrlClient", client)
        }

    private fun input(activity: AutoWithdrawSettingsActivity): EditText =
        activity.findViewById(R.id.lightning_address_input)

    private fun validation(activity: AutoWithdrawSettingsActivity): TextView =
        activity.findViewById(R.id.lightning_address_validation)

    private fun lookupJob(activity: AutoWithdrawSettingsActivity): Job =
        ReflectionHelpers.getField(activity, "thresholdFetchJob")

    private fun assertInvalid(activity: AutoWithdrawSettingsActivity) {
        assertEquals(View.VISIBLE, validation(activity).visibility)
        assertEquals(
            activity.getString(R.string.auto_withdraw_lightning_address_invalid),
            validation(activity).text.toString(),
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("Lookup did not reach the expected state", condition())
    }

    private fun details() = LnUrlClient.LnUrlPayResponse(
        callback = "https://example.com/callback",
        minSendable = 60_000_000,
        maxSendable = 1_000_000_000,
        metadata = "[]",
        tag = "payRequest",
    )
}
