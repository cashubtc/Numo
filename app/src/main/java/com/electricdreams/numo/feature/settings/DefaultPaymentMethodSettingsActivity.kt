package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.payment.DefaultPaymentMethodManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab

class DefaultPaymentMethodSettingsActivity : AppCompatActivity() {

    private lateinit var defaultPaymentMethodManager: DefaultPaymentMethodManager
    private lateinit var radioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_default_payment_method_settings)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        defaultPaymentMethodManager = DefaultPaymentMethodManager.getInstance(this)
        
        setupViews()
        setupListeners()
        loadCurrentPreference()
    }

    private fun setupViews() {
        radioGroup = findViewById(R.id.payment_method_radio_group)
    }

    private fun setupListeners() {
        findViewById<android.view.View>(R.id.back_button).setOnClickListener { finish() }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedTab = when (checkedId) {
                R.id.radio_unified -> PaymentTab.UNIFIED
                R.id.radio_cashu -> PaymentTab.CASHU
                R.id.radio_lightning -> PaymentTab.LIGHTNING
                else -> PaymentTab.UNIFIED
            }
            defaultPaymentMethodManager.setDefaultPaymentMethod(selectedTab)
        }
    }

    private fun loadCurrentPreference() {
        val currentMethod = defaultPaymentMethodManager.getDefaultPaymentMethod()
        val radioButtonId = when (currentMethod) {
            PaymentTab.UNIFIED -> R.id.radio_unified
            PaymentTab.CASHU -> R.id.radio_cashu
            PaymentTab.LIGHTNING -> R.id.radio_lightning
        }
        findViewById<RadioButton>(radioButtonId)?.isChecked = true
    }
}
