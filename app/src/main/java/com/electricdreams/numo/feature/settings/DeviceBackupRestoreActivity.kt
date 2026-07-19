package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.backup.DeviceRecoveryBackup
import com.electricdreams.numo.databinding.ActivityDeviceBackupRestoreBinding

/** Unlocks a recovery envelope that Android restored before Numo was launched. */
class DeviceBackupRestoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBackupRestoreBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceRecoveryBackup.hasRestoredBackup(this)) {
            Toast.makeText(this, R.string.restore_device_backup_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding = ActivityDeviceBackupRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, systemBars.top, 0, maxOf(systemBars.bottom, ime.bottom))
            insets
        }
        binding.topBar.onNavClick { finish() }
        binding.unlockBackupButton.setOnClickListener { unlockBackup() }
        binding.passwordInput.setOnEditorActionListener { _, _, _ ->
            unlockBackup()
            true
        }
    }

    private fun unlockBackup() {
        binding.passwordLayout.error = null
        try {
            val data = DeviceRecoveryBackup.decrypt(
                this,
                binding.passwordInput.text?.toString().orEmpty().toCharArray(),
            )
            binding.passwordInput.text?.clear()
            setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_MNEMONIC, data.mnemonic)
                    putStringArrayListExtra(EXTRA_MINTS, ArrayList(data.mints))
                },
            )
            finish()
        } catch (_: Exception) {
            binding.passwordLayout.error = getString(R.string.restore_device_backup_invalid_password)
        }
    }

    companion object {
        const val EXTRA_MNEMONIC = "device_backup_mnemonic"
        const val EXTRA_MINTS = "device_backup_mints"
    }
}
