package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrencyFlagsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `given Turkish locale, currency containing I resolves bundled flag`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        val context = ApplicationProvider.getApplicationContext<Context>()

        val flagResId = CurrencyFlags.flagResId(context, "inr")

        assertNotEquals(0, flagResId)
    }
}
