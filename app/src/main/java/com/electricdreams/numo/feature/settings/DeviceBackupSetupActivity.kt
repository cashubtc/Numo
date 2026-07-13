package com.electricdreams.numo.feature.settings

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

/** Opt-in setup for the single encrypted file included in Android Auto Backup. */
class DeviceBackupSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSetupDialog()
    }

    private fun showSetupDialog() {
        val padding = resources.getDimensionPixelSize(R.dimen.margin_screen_horizontal)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val password = passwordField(R.string.security_settings_device_backup_password_hint)
        val confirmation = passwordField(R.string.security_settings_device_backup_confirm_hint)
        container.addView(password)
        container.addView(confirmation)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.security_settings_device_backup_dialog_title)
            .setMessage(R.string.security_settings_device_backup_dialog_body)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.security_settings_device_backup_enable, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = password.text.toString()
                val confirmationValue = confirmation.text.toString()
                when {
                    value.length < 12 -> password.error = getString(R.string.security_settings_device_backup_password_short)
                    value != confirmationValue -> confirmation.error = getString(R.string.security_settings_device_backup_password_mismatch)
                    else -> enableBackup(dialog, password, confirmation, value)
                }
            }
        }
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun passwordField(hintRes: Int): EditText = EditText(this).apply {
        hint = getString(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun enableBackup(
        dialog: AlertDialog,
        password: EditText,
        confirmation: EditText,
        value: String,
    ) {
        try {
            DeviceRecoveryBackup.enable(this, value.toCharArray())
            password.text?.clear()
            confirmation.text?.clear()
            Toast.makeText(this, R.string.security_settings_device_backup_enabled, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        } catch (exception: Exception) {
            password.error = exception.message ?: getString(R.string.security_settings_device_backup_error)
        }
    }
}
