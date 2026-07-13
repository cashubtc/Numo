package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.backup.DeviceRecoveryBackup

/** Unlocks a recovery envelope that Android restored before Numo was launched. */
class DeviceBackupRestoreActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceRecoveryBackup.hasRestoredBackup(this)) {
            Toast.makeText(this, R.string.restore_device_backup_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        showPasswordDialog()
    }

    private fun showPasswordDialog() {
        val password = EditText(this).apply {
            hint = getString(R.string.restore_device_backup_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val padding = resources.getDimensionPixelSize(R.dimen.margin_screen_horizontal)
            setPadding(padding, 0, padding, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.restore_device_backup_title)
            .setMessage(R.string.restore_device_backup_body)
            .setView(password)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.restore_device_backup_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val data = DeviceRecoveryBackup.decrypt(this, password.text.toString().toCharArray())
                    password.text?.clear()
                    setResult(
                        Activity.RESULT_OK,
                        Intent().apply {
                            putExtra(EXTRA_MNEMONIC, data.mnemonic)
                            putStringArrayListExtra(EXTRA_MINTS, ArrayList(data.mints))
                        },
                    )
                    dialog.dismiss()
                    finish()
                } catch (_: Exception) {
                    password.error = getString(R.string.restore_device_backup_invalid_password)
                }
            }
        }
        dialog.setOnDismissListener { if (!isFinishing) finish() }
        dialog.show()
    }

    companion object {
        const val EXTRA_MNEMONIC = "device_backup_mnemonic"
        const val EXTRA_MINTS = "device_backup_mints"
    }
}
