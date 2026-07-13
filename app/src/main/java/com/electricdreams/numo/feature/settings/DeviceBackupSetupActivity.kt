package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.backup.DeviceRecoveryBackup
import com.electricdreams.numo.databinding.ActivityDeviceBackupSetupBinding

/** Opt-in setup for the single encrypted file included in Android Auto Backup. */
class DeviceBackupSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBackupSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBackupSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topBar.onNavClick { finish() }
        binding.enableBackupButton.setOnClickListener { enableBackup() }
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
                    Toast.makeText(this, R.string.security_settings_device_backup_enabled, Toast.LENGTH_SHORT).show()
                    finish()
                } catch (exception: Exception) {
                    binding.passwordLayout.error = exception.message
                        ?: getString(R.string.security_settings_device_backup_error)
                }
            }
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 12
    }
}
