package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.CurrencyManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class CurrencySettingsActivity : AppCompatActivity() {

    private lateinit var currencyRadioGroup: RadioGroup
    private lateinit var radioUsd: RadioButton
    private lateinit var radioEur: RadioButton
    private lateinit var radioGbp: RadioButton
    private lateinit var radioJpy: RadioButton
    private lateinit var radioDkk: RadioButton
    private lateinit var radioSek: RadioButton
    private lateinit var radioNok: RadioButton
    private lateinit var radioKrw: RadioButton
    private lateinit var currencyManager: CurrencyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        currencyManager = CurrencyManager.getInstance(this)

        currencyRadioGroup = findViewById(R.id.currency_radio_group)
        radioUsd = findViewById(R.id.radio_usd)
        radioEur = findViewById(R.id.radio_eur)
        radioGbp = findViewById(R.id.radio_gbp)
        radioJpy = findViewById(R.id.radio_jpy)
        radioDkk = findViewById(R.id.radio_dkk)
        radioSek = findViewById(R.id.radio_sek)
        radioNok = findViewById(R.id.radio_nok)
        radioKrw = findViewById(R.id.radio_krw)

        setSelectedCurrency(currencyManager.getCurrentCurrency())

        currencyRadioGroup.setOnCheckedChangeListener { _, _ ->
            val selectedCurrency = getSelectedCurrency()
            currencyManager.setPreferredCurrency(selectedCurrency)
        }
    }

    private fun setSelectedCurrency(currencyCode: String) {
        when (currencyCode) {
            CurrencyManager.CURRENCY_EUR -> radioEur.isChecked = true
            CurrencyManager.CURRENCY_GBP -> radioGbp.isChecked = true
            CurrencyManager.CURRENCY_JPY -> radioJpy.isChecked = true
            CurrencyManager.CURRENCY_USD -> radioUsd.isChecked = true
            CurrencyManager.CURRENCY_DKK -> radioDkk.isChecked = true
            CurrencyManager.CURRENCY_SEK -> radioSek.isChecked = true
            CurrencyManager.CURRENCY_NOK -> radioNok.isChecked = true
            CurrencyManager.CURRENCY_KRW -> radioKrw.isChecked = true
            else -> radioUsd.isChecked = true
        }
    }

    private fun getSelectedCurrency(): String {
        val selectedId = currencyRadioGroup.checkedRadioButtonId
        return when (selectedId) {
            R.id.radio_eur -> CurrencyManager.CURRENCY_EUR
            R.id.radio_gbp -> CurrencyManager.CURRENCY_GBP
            R.id.radio_jpy -> CurrencyManager.CURRENCY_JPY
            R.id.radio_dkk -> CurrencyManager.CURRENCY_DKK
            R.id.radio_sek -> CurrencyManager.CURRENCY_SEK
            R.id.radio_nok -> CurrencyManager.CURRENCY_NOK
            R.id.radio_krw -> CurrencyManager.CURRENCY_KRW
            else -> CurrencyManager.CURRENCY_USD
        }
    }
}
