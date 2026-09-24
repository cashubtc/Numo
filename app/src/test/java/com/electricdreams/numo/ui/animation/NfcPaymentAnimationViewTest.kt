package com.electricdreams.numo.ui.animation

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcPaymentAnimationViewTest {

    private lateinit var context: Context
    private lateinit var view: NfcPaymentAnimationView
    private val results = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = NfcPaymentAnimationView(activity)
        activity.setContentView(view)
        view.setOnResultDisplayedListener { results += it }
    }

    @After
    fun tearDown() {
        setAnimatorDurationScale(1f)
    }

    private fun setAnimatorDurationScale(scale: Float) {
        // Hidden framework API; Robolectric runs the real ValueAnimator.
        ValueAnimator::class.java
            .getMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, scale)
    }

    private fun runAnimations() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    }

    @Test
    fun `success is reported once the reveal finishes`() {
        view.showSuccess()
        assertTrue(results.isEmpty())

        runAnimations()

        assertEquals(listOf(true), results)
    }

    @Test
    fun `error is reported as not successful`() {
        view.showError()
        runAnimations()

        assertEquals(listOf(false), results)
    }

    @Test
    fun `a second result while one is showing is ignored`() {
        view.showSuccess()
        view.showError()
        runAnimations()

        assertEquals(listOf(true), results)
    }

    @Test
    fun `reset before the result settles does not report it`() {
        view.showSuccess()
        view.reset()
        runAnimations()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `after reset a new result can be shown`() {
        view.showError()
        runAnimations()
        view.reset()

        view.showSuccess()
        runAnimations()

        assertEquals(listOf(false, true), results)
    }

    @Test
    fun `with animations removed the result is reported without animating`() {
        setAnimatorDurationScale(0f)
        assertTrue(ReducedMotion.isEnabled(context))

        view.showSuccess()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(true), results)
    }

    @Test
    fun `reduced motion is off by default`() {
        assertFalse(ReducedMotion.isEnabled(context))
    }
}
