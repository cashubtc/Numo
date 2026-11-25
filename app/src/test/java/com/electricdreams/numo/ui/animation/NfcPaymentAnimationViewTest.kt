package com.electricdreams.numo.ui.animation

import android.content.Context
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NfcPaymentAnimationViewTest {

    private lateinit var context: Context
    private lateinit var view: NfcPaymentAnimationView

    @Before
    fun setUp() {
        context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Numo)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        view = NfcPaymentAnimationView(context)
        view.layout(0, 0, 1080, 2400)
    }

    @Test
    fun `surface cover draws opaque backdrop without disc pulses or orbit`() {
        view.showSurfaceCover()

        assertEquals(NfcPaymentAnimationView.State.SURFACE_COVER, view.currentState)
        assertEquals(0f, view.waitAlpha, 0.001f)
        assertEquals(0f, view.pulseAlpha, 0.001f)
        assertEquals(0f, view.orbitAlpha, 0.001f)
    }

    @Test
    fun `startProcessingDirectly enters filled processing without waiting pulses`() {
        view.startProcessingDirectly(animateIn = true)

        assertEquals(NfcPaymentAnimationView.State.PROCESSING, view.currentState)
        assertEquals(1f, view.processingProgress, 0.001f)
        assertEquals(0f, view.pulseAlpha, 0.001f)
        assertEquals(1f, view.waitAlpha, 0.001f)
    }

    @Test
    fun `direct success from idle never flashes contactless waiting waves or pulses`() {
        view.showSuccessDirect("$12.50")

        assertEquals(NfcPaymentAnimationView.State.RESULT_TRANSITION, view.currentState)
        assertEquals(0f, view.waitAlpha, 0.001f)
        assertEquals(0f, view.pulseAlpha, 0.001f)
        assertEquals(0f, view.orbitAlpha, 0.001f)
    }

    @Test
    fun `showSuccess from idle or surface cover stays direct without calling startReading`() {
        view.showSurfaceCover()
        view.showSuccess("$12.50")

        assertEquals(NfcPaymentAnimationView.State.RESULT_TRANSITION, view.currentState)
        assertEquals(0f, view.waitAlpha, 0.001f)
        assertEquals(0f, view.pulseAlpha, 0.001f)
    }

    @Test
    fun `waiting waves finish fading out before result icon begins drawing`() {
        assertTrue(
            "Waves must fade out (${NfcPaymentAnimationView.WAIT_FADE_OUT_MS}ms) before check starts (${NfcPaymentAnimationView.RESULT_ICON_DELAY_MS}ms)",
            NfcPaymentAnimationView.WAIT_FADE_OUT_MS < NfcPaymentAnimationView.RESULT_ICON_DELAY_MS,
        )
    }

    @Test
    fun `reduced motion completes result immediately and notifies listener`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var callbackSuccess: Boolean? = null
        view.setOnResultDisplayedListener { callbackSuccess = it }

        view.showSuccessDirect("$5.00")

        assertEquals(NfcPaymentAnimationView.State.RESULT, view.currentState)
        assertEquals(1f, view.resultIconProgress, 0.001f)
        assertEquals(true, callbackSuccess)
    }

    @Test
    fun `geometry callback fires on layout with positive discBottom`() {
        var notified = false
        val freshView = NfcPaymentAnimationView(context)
        freshView.setOnGeometryChangedListener { notified = true }

        freshView.layout(0, 0, 1080, 2400)

        assertTrue(notified)
        assertTrue(freshView.discBottom > 0f)
    }

    @Test
    @Config(qualifiers = "night")
    fun `dark mode active contactless waves have legible contrast on active disc`() {
        val disc = ContextCompat.getColor(context, R.color.color_tap_disc_active)
        val symbol = ContextCompat.getColor(context, R.color.color_tap_symbol_active)
        val contrast = ColorUtils.calculateContrast(symbol, disc)

        assertTrue("Expected contrast >= 4.5 in dark mode, got $contrast", contrast >= 4.5)
        assertFalse(disc == symbol)
    }
}
