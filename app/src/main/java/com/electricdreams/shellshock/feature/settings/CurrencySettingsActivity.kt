package com.electricdreams.shellshock.feature.settings

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.util.CurrencyManager

class CurrencySettingsActivity : AppCompatActivity() {

    private lateinit var currencyRadioGroup: RadioGroup
    private lateinit var radioUsd: RadioButton
    private lateinit var radioEur: RadioButton
    private lateinit var radioGbp: RadioButton
    private lateinit var radioJpy: RadioButton
    private lateinit var currencyManager: CurrencyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        currencyManager = CurrencyManager.getInstance(this)

        currencyRadioGroup = findViewById(R.id.currency_radio_group)
        radioUsd = findViewById(R.id.radio_usd)
        radioEur = findViewById(R.id.radio_eur)
        radioGbp = findViewById(R.id.radio_gbp)
        radioJpy = findViewById(R.id.radio_jpy)

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
            else -> radioUsd.isChecked = true
        }
    }

    private fun getSelectedCurrency(): String {
        val selectedId = currencyRadioGroup.checkedRadioButtonId
        return when (selectedId) {
            R.id.radio_eur -> CurrencyManager.CURRENCY_EUR
            R.id.radio_gbp -> CurrencyManager.CURRENCY_GBP
            R.id.radio_jpy -> CurrencyManager.CURRENCY_JPY
            else -> CurrencyManager.CURRENCY_USD
        }
    }
}
