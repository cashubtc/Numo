package com.electricdreams.numo.core.util

import android.content.Context
import android.content.SharedPreferences
import com.electricdreams.numo.core.model.Amount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyString
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CurrencyManagerTest {

    private lateinit var currencyManager: CurrencyManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        
        // Reset singleton
        val instanceField = CurrencyManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        currencyManager = CurrencyManager.getInstance(context)
    }

    @Test
    fun `default currency is USD`() {
        // We need to clear prefs first because RuntimeEnvironment persists them across tests
        val prefs = context.getSharedPreferences("CurrencyPreferences", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        
        // Re-init after clearing prefs
        val instanceField = CurrencyManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
        currencyManager = CurrencyManager.getInstance(context)

        assertEquals("USD", currencyManager.getCurrentCurrency())
        assertEquals("$", currencyManager.getCurrentSymbol())
    }

    @Test
    fun `setPreferredCurrency updates currency and notifies listener`() {
        var callbackCalled = false
        currencyManager.setCurrencyChangeListener(object : CurrencyManager.CurrencyChangeListener {
            override fun onCurrencyChanged(newCurrency: String) {
                callbackCalled = true
                assertEquals("EUR", newCurrency)
            }
        })

        val success = currencyManager.setPreferredCurrency("EUR")
        
        assertTrue(success)
        assertTrue(callbackCalled)
        assertEquals("EUR", currencyManager.getCurrentCurrency())
        assertEquals("€", currencyManager.getCurrentSymbol())
    }

    @Test
    fun `setPreferredCurrency rejects invalid code`() {
        val success = currencyManager.setPreferredCurrency("INVALID")
        assertFalse(success)
        assertEquals("USD", currencyManager.getCurrentCurrency()) // Should remain unchanged (assuming default)
    }

    @Test
    fun `isValidCurrency checks supported currencies`() {
        assertTrue(currencyManager.isValidCurrency("USD"))
        assertTrue(currencyManager.isValidCurrency("EUR"))
        assertTrue(currencyManager.isValidCurrency("GBP"))
        assertTrue(currencyManager.isValidCurrency("JPY"))
        
        assertFalse(currencyManager.isValidCurrency("CAD"))
        assertFalse(currencyManager.isValidCurrency(""))
        assertFalse(currencyManager.isValidCurrency(null))
    }

    @Test
    fun `formatCurrencyAmount formats correctly for locale`() {
        currencyManager.setPreferredCurrency("USD")
        assertEquals("$10.50", currencyManager.formatCurrencyAmount(10.50))
        
        currencyManager.setPreferredCurrency("EUR")
        // Amount class formatting might depend on locale, but let's check basic symbol
        val formatted = currencyManager.formatCurrencyAmount(10.50)
        assertTrue(formatted.contains("€"))
    }
    
    @Test
    fun `getCoinbaseApiUrl returns correct URL`() {
        currencyManager.setPreferredCurrency("EUR")
        assertEquals("https://api.coinbase.com/v2/prices/BTC-EUR/spot", currencyManager.getCoinbaseApiUrl())
        
        currencyManager.setPreferredCurrency("USD")
        assertEquals("https://api.coinbase.com/v2/prices/BTC-USD/spot", currencyManager.getCoinbaseApiUrl())
    }
}
