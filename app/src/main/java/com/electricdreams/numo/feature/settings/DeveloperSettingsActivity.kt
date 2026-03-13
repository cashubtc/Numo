package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityDeveloperSettingsBinding
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.feature.onboarding.OnboardingActivity

class DeveloperSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeveloperSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeveloperSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton?.setOnClickListener { finish() }

        binding.restartOnboardingItem.setOnClickListener {
            showRestartOnboardingDialog()
        }

        binding.errorLogsItem.setOnClickListener {
            startActivity(Intent(this, ErrorLogsActivity::class.java))
        }

        // Delay Lightning Invoice
        binding.delayLightningInvoiceSwitch.isChecked = DeveloperPrefs.isLightningInvoiceDelayed(this)

        binding.delayLightningInvoiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            DeveloperPrefs.setLightningInvoiceDelayed(this, isChecked)
        }

        binding.delayLightningInvoiceItem.setOnClickListener {
            binding.delayLightningInvoiceSwitch.toggle()
        }
    }

    private fun showRestartOnboardingDialog() {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.developer_settings_restart_dialog_title),
                message = getString(R.string.developer_settings_restart_dialog_message),
                confirmText = getString(R.string.developer_settings_restart_dialog_positive),
                cancelText = getString(R.string.common_cancel),
                isDestructive = false,
                onConfirm = {
                    // Clear onboarding completion status
                    OnboardingActivity.setOnboardingComplete(this, false)

                    // Navigate to onboarding
                    val intent = Intent(this, OnboardingActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            )
        )
    }
}
