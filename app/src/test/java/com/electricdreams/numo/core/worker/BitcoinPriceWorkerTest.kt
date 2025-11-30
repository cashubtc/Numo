package com.electricdreams.numo.core.worker

import android.content.Context
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BitcoinPriceWorkerTest {

    private lateinit var context: Context
    private lateinit var worker: BitcoinPriceWorker
    private lateinit var currencyManager: CurrencyManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        // Clear prefs for price worker
        val prefs = context.getSharedPreferences("BitcoinPricePrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        currencyManager = CurrencyManager.getInstance(context)
        currencyManager.setPreferredCurrency(CurrencyManager.CURRENCY_USD)

        // Instantiate the worker via reflection (constructor is private)
        val ctor = BitcoinPriceWorker::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        worker = ctor.newInstance(context)

        // Seed internal price map
        val field = BitcoinPriceWorker::class.java.getDeclaredField("priceByCurrency")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(worker) as MutableMap<String, Double>
        map.clear()
        map[CurrencyManager.CURRENCY_USD] = 50_000.0
        map[CurrencyManager.CURRENCY_EUR] = 45_000.0
    }

    @Test
    fun `getCurrentPrice follows currency manager`() {
        currencyManager.setPreferredCurrency(CurrencyManager.CURRENCY_USD)
        assertEquals(50_000.0, worker.getCurrentPrice(), 0.0001)

        currencyManager.setPreferredCurrency(CurrencyManager.CURRENCY_EUR)
        assertEquals(45_000.0, worker.getCurrentPrice(), 0.0001)
    }

    @Test
    fun `satoshisToFiat converts using current price`() {
        currencyManager.setPreferredCurrency(CurrencyManager.CURRENCY_USD)
        val fiat = worker.satoshisToFiat(100_000_000L) // 1 BTC
        assertEquals(50_000.0, fiat, 0.0001)
    }

    @Test
    fun `satoshisToFiat returns zero when no price`() {
        val field = BitcoinPriceWorker::class.java.getDeclaredField("priceByCurrency")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(worker) as MutableMap<String, Double>
        map.clear()

        val fiat = worker.satoshisToFiat(100_000_000L)
        assertEquals(0.0, fiat, 0.0001)
    }

    @Test
    fun `formatFiatAmount uses Amount via CurrencyManager`() {
        currencyManager.setPreferredCurrency(CurrencyManager.CURRENCY_USD)
        val formatted = worker.formatFiatAmount(12.34)
        // 12.34 -> 1234 minor units
        assertTrue(formatted.contains("12.34"))
    }

    @Test
    fun `formatUsdAmount uses Amount helper`() {
        val formatted = worker.formatUsdAmount(1.23)
        // 1.23 -> 123 cents
        assertTrue(formatted.contains("1.23"))
    }
}
