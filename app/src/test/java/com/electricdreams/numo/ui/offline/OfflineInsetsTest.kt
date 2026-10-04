package com.electricdreams.numo.ui.offline

import android.app.Application
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineInsetsTest {

    private val insets = WindowInsetsCompat.Builder()
        .setInsets(Type.statusBars(), Insets.of(0, 60, 0, 0))
        .setInsets(Type.navigationBars(), Insets.of(0, 0, 0, 40))
        .setInsets(Type.ime(), Insets.of(0, 0, 0, 300))
        .build()

    @Test
    fun `given the strip is hidden, then insets pass through untouched`() {
        assertSame(insets, insets.withExtraStatusBarTop(0))
    }

    @Test
    fun `given the strip is showing, then screens see a taller status bar`() {
        val adjusted = insets.withExtraStatusBarTop(120)

        assertEquals(Insets.of(0, 180, 0, 0), adjusted.getInsets(Type.statusBars()))
        assertEquals(180, adjusted.getInsets(Type.systemBars()).top)
    }

    @Test
    fun `given the strip is showing, then the bottom bars and keyboard are untouched`() {
        val adjusted = insets.withExtraStatusBarTop(120)

        assertEquals(Insets.of(0, 0, 0, 40), adjusted.getInsets(Type.navigationBars()))
        assertEquals(Insets.of(0, 0, 0, 300), adjusted.getInsets(Type.ime()))
    }
}
