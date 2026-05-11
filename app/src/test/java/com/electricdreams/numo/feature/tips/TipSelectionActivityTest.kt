package com.electricdreams.numo.feature.tips

import android.content.Context
import android.content.Intent
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TipSelectionActivityTest {

    private lateinit var activity: TipSelectionActivity

    @Before
    fun setUp() {
        val intent = Intent().apply {
            putExtra(TipSelectionActivity.EXTRA_PAYMENT_AMOUNT, 1000L)
            putExtra(TipSelectionActivity.EXTRA_FORMATTED_AMOUNT, "$10.00")
        }
        activity = Robolectric.buildActivity(TipSelectionActivity::class.java, intent)
            .create()
            .get()
    }

    @Test
    fun `customInputCurrency is initialized from entryCurrency when order is in fiat`() {
        val entryCurrency = activity.javaClass.getDeclaredField("entryCurrency").let { field ->
            field.isAccessible = true
            field.get(activity) as Currency
        }
        
        val customInputCurrency = activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.get(activity) as Currency
        }

        assertEquals(entryCurrency, customInputCurrency)
    }

    @Test
    fun `customInputCurrency is initialized from entryCurrency when order is in sats`() {
        val intent = Intent().apply {
            putExtra(TipSelectionActivity.EXTRA_PAYMENT_AMOUNT, 1000L)
            putExtra(TipSelectionActivity.EXTRA_FORMATTED_AMOUNT, "₿1000")
        }
        val satActivity = Robolectric.buildActivity(TipSelectionActivity::class.java, intent)
            .create()
            .get()

        val entryCurrency = satActivity.javaClass.getDeclaredField("entryCurrency").let { field ->
            field.isAccessible = true
            field.get(satActivity) as Currency
        }
        
        val customInputCurrency = satActivity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.get(satActivity) as Currency
        }

        assertEquals(Currency.BTC, entryCurrency)
        assertEquals(Currency.BTC, customInputCurrency)
    }

    @Test
    fun `toggleCustomCurrency switches customInputCurrency from BTC to fiat`() {
        activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.set(activity, true)
        }
        activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.BTC)
        }

        val toggleMethod = activity.javaClass.getDeclaredMethod("toggleCustomCurrency")
        toggleMethod.isAccessible = true
        toggleMethod.invoke(activity)

        val customInputIsBtc = activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.get(activity) as Boolean
        }
        val customInputCurrency = activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.get(activity) as Currency
        }

        assertFalse(customInputIsBtc)
        assertNotEquals(Currency.BTC, customInputCurrency)
    }

    @Test
    fun `toggleCustomCurrency switches customInputCurrency from fiat to BTC`() {
        activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.set(activity, false)
        }
        activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.USD)
        }

        val toggleMethod = activity.javaClass.getDeclaredMethod("toggleCustomCurrency")
        toggleMethod.isAccessible = true
        toggleMethod.invoke(activity)

        val customInputIsBtc = activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.get(activity) as Boolean
        }
        val customInputCurrency = activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.get(activity) as Currency
        }

        assertTrue(customInputIsBtc)
        assertEquals(Currency.BTC, customInputCurrency)
    }

    @Test
    fun `getCurrentFiatCurrency returns system currency`() {
        val getCurrentFiatCurrency = activity.javaClass.getDeclaredMethod("getCurrentFiatCurrency")
        getCurrentFiatCurrency.isAccessible = true
        val result = getCurrentFiatCurrency.invoke(activity) as Currency

        assertNotNull(result)
        assertNotEquals(Currency.BTC, result)
    }

    @Test
    fun `updateCustomCurrencyDisplay uses customInputCurrency for prefix`() {
        activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.set(activity, false)
        }
        activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.EUR)
        }
        activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.EUR)
        }

        val updateDisplay = activity.javaClass.getDeclaredMethod("updateCustomCurrencyDisplay")
        updateDisplay.isAccessible = true
        updateDisplay.invoke(activity)

        val currencyPrefix = activity.javaClass.getDeclaredField("customCurrencyPrefix").let { field ->
            field.isAccessible = true
            field.get(activity) as android.widget.TextView
        }

        assertEquals("€", currencyPrefix.text.toString())
    }

    @Test
    fun `updateCustomCurrencyDisplay shows BTC symbol when customInputIsBtc is true`() {
        activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.set(activity, true)
        }
        activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.BTC)
        }

        val updateDisplay = activity.javaClass.getDeclaredMethod("updateCustomCurrencyDisplay")
        updateDisplay.isAccessible = true
        updateDisplay.invoke(activity)

        val currencyPrefix = activity.javaClass.getDeclaredField("customCurrencyPrefix").let { field ->
            field.isAccessible = true
            field.get(activity) as android.widget.TextView
        }

        assertEquals("₿", currencyPrefix.text.toString())
    }

    @Test
    fun `toggleCustomCurrency clears customInputValue`() {
        activity.javaClass.getDeclaredField("customInputValue").let { field ->
            field.isAccessible = true
            field.set(activity, "500")
        }

        val toggleMethod = activity.javaClass.getDeclaredMethod("toggleCustomCurrency")
        toggleMethod.isAccessible = true
        toggleMethod.invoke(activity)

        val customInputValue = activity.javaClass.getDeclaredField("customInputValue").let { field ->
            field.isAccessible = true
            field.get(activity) as String
        }

        assertEquals("", customInputValue)
    }

    @Test
    fun `toggleCustomCurrency from BTC restores entryCurrency if it was fiat`() {
        // Set entry currency to EUR
        activity.javaClass.getDeclaredField("entryCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.EUR)
        }
        
        // Setup current state to BTC mode
        activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.set(activity, true)
        }

        // Toggle back to fiat
        val toggleMethod = activity.javaClass.getDeclaredMethod("toggleCustomCurrency")
        toggleMethod.isAccessible = true
        toggleMethod.invoke(activity)

        val customInputIsBtc = activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.get(activity) as Boolean
        }
        val customInputCurrency = activity.javaClass.getDeclaredField("customInputCurrency").let { field ->
            field.isAccessible = true
            field.get(activity) as Currency
        }

        assertFalse(customInputIsBtc)
        // Should restore to EUR, not system default fiat currency
        assertEquals(Currency.EUR, customInputCurrency)
    }

    @Test
    fun `updateCustomCurrencyDisplay shows correct fiat symbol on toggle button when in BTC mode`() {
        // Set entry currency to EUR
        activity.javaClass.getDeclaredField("entryCurrency").let { field ->
            field.isAccessible = true
            field.set(activity, Currency.EUR)
        }
        
        // Setup current state to BTC mode
        activity.javaClass.getDeclaredField("customInputIsBtc").let { field ->
            field.isAccessible = true
            field.set(activity, true)
        }

        val updateDisplay = activity.javaClass.getDeclaredMethod("updateCustomCurrencyDisplay")
        updateDisplay.isAccessible = true
        updateDisplay.invoke(activity)

        val customCurrencyToggle = activity.javaClass.getDeclaredField("customCurrencyToggle").let { field ->
            field.isAccessible = true
            field.get(activity) as android.widget.TextView
        }

        val toggleText = customCurrencyToggle.text.toString()
        assertTrue("Toggle text should contain EUR symbol. Was: $toggleText", toggleText.contains("€"))
    }
}
