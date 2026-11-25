package com.electricdreams.numo.payment.card

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StripeTapToPayUxTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "notnight")
    fun `tap screen is light with Numo colours in the light theme`() {
        val style = StripeTerminalManager.tapScreenStyle(context)

        assertFalse(style.dark)
        assertEquals(color(R.color.color_primary_green), style.primary)
        assertEquals(color(R.color.color_nfc_success), style.success)
        assertEquals(color(R.color.color_error), style.error)
    }

    @Test
    @Config(qualifiers = "night")
    fun `tap screen is dark with Numo colours in the dark theme`() {
        val style = StripeTerminalManager.tapScreenStyle(context)

        assertTrue(style.dark)
        assertEquals(color(R.color.color_primary_green), style.primary)
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
}
