package com.electricdreams.numo.feature.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import com.electricdreams.numo.R
import com.electricdreams.numo.core.backup.DeviceRecoveryBackup
import com.electricdreams.numo.databinding.ActivityDeviceBackupSetupBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/** Opt-in setup for the single encrypted file included in Android Auto Backup. */
class DeviceBackupSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBackupSetupBinding
    private var backupEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBackupSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        binding.topBar.onNavClick { finish() }
        backupEnabled = DeviceRecoveryBackup.isEnabled(this)
        if (backupEnabled) showActivatedState()
        binding.enableBackupButton.setOnClickListener {
            if (backupEnabled) finish() else enableBackup()
        }
        binding.confirmationInput.setOnEditorActionListener { _, _, _ ->
            enableBackup()
            true
        }
    }

    private fun enableBackup() {
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirmation = binding.confirmationInput.text?.toString().orEmpty()
        binding.passwordLayout.error = null
        binding.confirmationLayout.error = null

        when {
            password.length < MIN_PASSWORD_LENGTH -> {
                binding.passwordLayout.error = getString(R.string.security_settings_device_backup_password_short)
            }
            password != confirmation -> {
                binding.confirmationLayout.error = getString(R.string.security_settings_device_backup_password_mismatch)
            }
            else -> {
                try {
                    DeviceRecoveryBackup.enable(this, password.toCharArray())
                    binding.passwordInput.text?.clear()
                    binding.confirmationInput.text?.clear()
                    showActivatedState()
                } catch (exception: Exception) {
                    binding.passwordLayout.error = exception.message
                        ?: getString(R.string.security_settings_device_backup_error)
                }
            }
        }
    }

    private fun showActivatedState() {
        backupEnabled = true
        binding.setupForm.visibility = android.view.View.GONE
        binding.successState.visibility = android.view.View.VISIBLE
        binding.enableBackupButton.setText(R.string.security_settings_device_backup_done)
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 12
    }
}
