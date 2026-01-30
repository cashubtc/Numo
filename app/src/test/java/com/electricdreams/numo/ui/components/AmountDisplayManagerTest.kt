package com.electricdreams.numo.ui.components

import android.content.Context
import android.widget.Button
import android.widget.TextView
import android.view.View
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.mockito.kotlin.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class AmountDisplayManagerTest {

    @Mock
    private lateinit var amountDisplay: TextView

    @Mock
    private lateinit var secondaryAmountDisplay: TextView

    @Mock
    private lateinit var switchCurrencyButton: View

    @Mock
    private lateinit var submitButton: Button

    @Mock
    private lateinit var bitcoinPriceWorker: BitcoinPriceWorker

    @Mock
    private lateinit var mockContext: Context

    private lateinit var realContext: Context
    private lateinit var manager: AmountDisplayManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        realContext = RuntimeEnvironment.getApplication()

        // Setup Mock Context
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.getSharedPreferences(any(), any())).thenAnswer { invocation ->
            val name = invocation.arguments[0] as String
            val mode = invocation.arguments[1] as Int
            realContext.getSharedPreferences(name, mode)
        }
        `when`(mockContext.getString(eq(R.string.pos_charge_button))).thenReturn("Charge")
        `when`(mockContext.getString(any())).thenReturn("String")
        `when`(mockContext.resources).thenReturn(realContext.resources)

        // Reset CurrencyManager singleton
        resetCurrencyManager()
        // Reset PreferenceStore singleton
        resetPreferenceStore()

        // Set JPY in preferences
        val prefs = mockContext.getSharedPreferences("CurrencyPreferences", Context.MODE_PRIVATE)
        prefs.edit().putString("preferredCurrency", "JPY").commit()

        // Mock Bitcoin Price: 1 BTC = 10,000,000 JPY (1 sat = 0.1 JPY)
        `when`(bitcoinPriceWorker.getCurrentPrice()).thenReturn(10_000_000.0)
        `when`(bitcoinPriceWorker.satoshisToFiat(any())).thenAnswer { 
            val sats = it.arguments[0] as Long
            sats * 0.1
        }
        `when`(bitcoinPriceWorker.formatFiatAmount(any())).thenReturn("¥100") // Stub to prevent NPE
        
        // Mock TextView.text to return empty string to avoid NPE on initial read
        `when`(amountDisplay.text).thenReturn("")
        `when`(secondaryAmountDisplay.text).thenReturn("")
    }

    private fun resetCurrencyManager() {
        try {
            val instanceField: Field = CurrencyManager::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resetPreferenceStore() {
        try {
            val storesField: Field = com.electricdreams.numo.core.prefs.PreferenceStore::class.java.getDeclaredField("stores")
            storesField.isAccessible = true
            val map = storesField.get(null) as java.util.Map<*, *>
            map.clear()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Test
    fun `test JPY input shows whole units`() {
        // Initialize manager
        manager = AmountDisplayManager(
            mockContext,
            amountDisplay,
            secondaryAmountDisplay,
            switchCurrencyButton,
            submitButton,
            bitcoinPriceWorker
        )
        
        // Force input mode to USD/Fiat
        val prefs = mockContext.getSharedPreferences("numo_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("inputMode", true).commit()
        manager.initializeInputMode()

        val satoshiInput = StringBuilder()
        val fiatInput = StringBuilder("1") // User typed "1"

        // Update display
        manager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)

        // Verify: Input "1" should result in "¥1", not "¥0" (which would happen if treated as cents)
        verify(amountDisplay).text = "¥1"
        
        // Verify secondary display (BTC)
        // 1 JPY = 10 sats (at 10M JPY/BTC price)
        verify(secondaryAmountDisplay).text = "₿10"
    }
    
    @Test
    fun `test JPY input large amount`() {
        // Initialize manager
        manager = AmountDisplayManager(
            mockContext,
            amountDisplay,
            secondaryAmountDisplay,
            switchCurrencyButton,
            submitButton,
            bitcoinPriceWorker
        )
        
        // Force input mode to USD/Fiat
        val prefs = mockContext.getSharedPreferences("numo_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("inputMode", true).commit()
        manager.initializeInputMode()

        val satoshiInput = StringBuilder()
        val fiatInput = StringBuilder("123") // User typed "123"

        // Update display
        manager.updateDisplay(satoshiInput, fiatInput, AmountDisplayManager.AnimationType.NONE)

        // Verify: Input "123" should result in "¥123"
        verify(amountDisplay).text = "¥123"
    }

    @Test
    fun `test toggle from JPY to Sats`() {
        // Initialize manager
        manager = AmountDisplayManager(
            mockContext,
            amountDisplay,
            secondaryAmountDisplay,
            switchCurrencyButton,
            submitButton,
            bitcoinPriceWorker
        )
        
        // Force input mode to USD/Fiat (JPY)
        val prefs = mockContext.getSharedPreferences("numo_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("inputMode", true).commit()
        manager.initializeInputMode()

        val satoshiInput = StringBuilder()
        val fiatInput = StringBuilder("100") // 100 JPY

        // Toggle to Sats
        manager.toggleInputMode(satoshiInput, fiatInput)

        // 100 JPY = 1000 sats (at 10M JPY/BTC)
        assert(satoshiInput.toString() == "1000")
        assert(!manager.isUsdInputMode)
    }

    @Test
    fun `test toggle from Sats to JPY`() {
        // Initialize manager
        manager = AmountDisplayManager(
            mockContext,
            amountDisplay,
            secondaryAmountDisplay,
            switchCurrencyButton,
            submitButton,
            bitcoinPriceWorker
        )
        
        // Force input mode to Sats
        val prefs = mockContext.getSharedPreferences("numo_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("inputMode", false).commit()
        manager.initializeInputMode()

        val satoshiInput = StringBuilder("1000") // 1000 sats
        val fiatInput = StringBuilder()

        // Toggle to JPY
        manager.toggleInputMode(satoshiInput, fiatInput)

        // 1000 sats = 100 JPY (at 10M JPY/BTC)
        assert(fiatInput.toString() == "100")
        assert(manager.isUsdInputMode)
    }
}
