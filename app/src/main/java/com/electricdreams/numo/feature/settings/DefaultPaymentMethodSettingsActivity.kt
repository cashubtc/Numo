package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityDefaultPaymentMethodSettingsBinding
import com.electricdreams.numo.payment.DefaultPaymentMethodManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

class DefaultPaymentMethodSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDefaultPaymentMethodSettingsBinding

    private lateinit var defaultPaymentMethodManager: DefaultPaymentMethodManager
    private lateinit var radioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDefaultPaymentMethodSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        defaultPaymentMethodManager = DefaultPaymentMethodManager.getInstance(this)

        setupViews()
        setupListeners()
        loadCurrentPreference()
    }

    private fun setupViews() {
        radioGroup = binding.paymentMethodRadioGroup
    }

    private fun setupListeners() {
        binding.topBar.onNavClick { finish() }

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
        radioGroup.check(radioButtonId)
    }
}
