package com.electricdreams.numo.feature.pin

import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.core.view.children
import com.electricdreams.numo.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinEntryActivityTest {
    private lateinit var manager: PinManager

    @Before
    fun setUp() {
        manager = mock()
        setManager(manager)
    }

    @After
    fun tearDown() {
        setManager(null)
    }

    @Test
    fun `four digit PIN requires confirmation`() = confirmPin("1234")

    @Test
    fun `five digit PIN can be entered and confirmed`() = confirmPin("12345")

    @Test
    fun `six digit PIN can be entered and confirmed`() = confirmPin("123456")

    @Test
    fun `sixteen digit PIN can be entered and confirmed`() = confirmPin("1234567890123456")

    private fun confirmPin(pin: String) {
        whenever(manager.validatePin(pin)).thenReturn(PinManager.ValidationResult(success = true))
        Robolectric.buildActivity(PinEntryActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val confirm = activity.findViewById<Button>(R.id.confirm_button)
            assertFalse(confirm.isEnabled)
            pin.forEach { digit ->
                pressDigit(activity, digit)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            }
            verify(manager, never()).validatePin(any())
            assertTrue(confirm.isEnabled)
            confirm.performClick()
            confirm.performClick()
            verify(manager).validatePin(pin)
            assertFalse(confirm.isEnabled)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertTrue(activity.isFinishing)
            assertEquals(PinEntryActivity.RESULT_PIN_VERIFIED, shadowOf(activity).resultCode)
        }
    }

    @Test
    fun `deleting below minimum length disables confirmation`() {
        Robolectric.buildActivity(PinEntryActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            repeat(4) { pressDigit(activity, '1') }
            val keypad = activity.findViewById<PinKeypadView>(R.id.pin_keypad)
            keypad.children.filterIsInstance<ImageButton>().single().performClick()
            assertFalse(activity.findViewById<Button>(R.id.confirm_button).isEnabled)
            verify(manager, never()).validatePin(any())
        }
    }

    @Test
    fun `failed confirmation blocks repeat attempts until cooldown finishes`() {
        whenever(manager.validatePin("1111"))
            .thenReturn(PinManager.ValidationResult(success = false))
        Robolectric.buildActivity(PinEntryActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            repeat(4) { pressDigit(activity, '1') }
            val confirm = activity.findViewById<Button>(R.id.confirm_button)
            confirm.performClick()
            repeat(4) { pressDigit(activity, '1') }
            confirm.performClick()
            verify(manager).validatePin("1111")
            assertFalse(confirm.isEnabled)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            repeat(4) { pressDigit(activity, '1') }
            assertTrue(confirm.isEnabled)
        }
    }

    @Test
    fun `recovery remains available during lockout`() {
        whenever(manager.getRemainingLockoutMs()).thenReturn(60_000L)
        Robolectric.buildActivity(PinEntryActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.lockout_overlay).visibility)
            activity.findViewById<View>(R.id.lockout_reset_button).performClick()
            assertEquals(PinResetActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
        }
    }

    private fun pressDigit(activity: PinEntryActivity, digit: Char) {
        activity.findViewById<PinKeypadView>(R.id.pin_keypad).children
            .filterIsInstance<Button>().first { it.text.toString() == digit.toString() }
            .performClick()
    }

    private fun setManager(value: PinManager?) {
        PinManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, value)
        }
    }
}
