package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

import com.electricdreams.numo.R
import com.electricdreams.numo.core.backup.DeviceRecoveryBackup
import com.electricdreams.numo.databinding.ActivitySecuritySettingsBinding
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinSetupActivity
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.applySettingsWindowInsets
import com.electricdreams.numo.util.startActivityForResultCompat

class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecuritySettingsBinding

    private lateinit var pinManager: PinManager

    private lateinit var setupPinItem: View
    private lateinit var changePinItem: View
    private lateinit var removePinItem: View

    private var pendingAction: PendingAction? = null

    private enum class PendingAction {
        BACKUP_MNEMONIC,
        ENABLE_DEVICE_BACKUP,
        RESTORE_WALLET,
        CHANGE_PIN,
        REMOVE_PIN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecuritySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        pinManager = PinManager.getInstance(this)

        setupPinItem = binding.setupPinItem
        changePinItem = binding.changePinItem
        removePinItem = binding.removePinItem

        binding.topBar.onNavClick { finish() }

        // Static text is now set directly in XML for cleaner layout; no explicit binding needed here

        updatePinUI()

        // Setup PIN
        setupPinItem.setOnClickListener {
            startActivityForResultCompat(
                Intent(this, PinSetupActivity::class.java),
                REQUEST_PIN_SETUP
            )
        }

        // Change PIN - requires PIN verification
        changePinItem.setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.CHANGE_PIN
                requestPinVerification()
            } else {
                openChangePin()
            }
        }

        // Remove PIN - requires PIN verification
        removePinItem.setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.REMOVE_PIN
                requestPinVerification()
            }
        }

        // Backup mnemonic - requires PIN if set
        binding.backupMnemonicItem.setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.BACKUP_MNEMONIC
                requestPinVerification()
            } else {
                openBackupMnemonic()
            }
        }

        binding.deviceBackupItem.setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.ENABLE_DEVICE_BACKUP
                requestPinVerification()
            } else {
                openDeviceBackupSetup()
            }
        }

        // Restore wallet - requires PIN if set
        binding.restoreWalletItem.setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.RESTORE_WALLET
                requestPinVerification()
            } else {
                openRestoreWallet()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        updateBackupUI()
    }

    private fun updateBackupUI() {
        val isBackupEnabled = DeviceRecoveryBackup.isEnabled(this)
        val deviceBackupItem = binding.deviceBackupItem
        if (isBackupEnabled) {
            deviceBackupItem.setSubtitle(getString(R.string.security_settings_device_backup_enabled_subtitle))
        } else {
            deviceBackupItem.setSubtitle(getString(R.string.security_settings_device_backup_subtitle))
        }
    }

    private fun updatePinUI() {
        val isPinSet = pinManager.isPinEnabled()

        if (isPinSet) {
            // PIN is set - show change/remove options
            setupPinItem.visibility = View.GONE
            changePinItem.visibility = View.VISIBLE
            removePinItem.visibility = View.VISIBLE
        } else {
            // No PIN - show setup option
            setupPinItem.visibility = View.VISIBLE
            changePinItem.visibility = View.GONE
            removePinItem.visibility = View.GONE
        }
    }

    private fun requestPinVerification() {
        val intent = Intent(this, PinEntryActivity::class.java).apply {
            putExtra(PinEntryActivity.EXTRA_TITLE, getString(R.string.security_settings_enter_pin_title))
            putExtra(PinEntryActivity.EXTRA_SUBTITLE, getString(R.string.security_settings_enter_pin_subtitle))
        }
        startActivityForResultCompat(intent, REQUEST_PIN_VERIFY)
    }

    private fun openDeviceBackupSetup() {
        startActivity(Intent(this, DeviceBackupSetupActivity::class.java))
    }

    private fun openBackupMnemonic() {
        startActivity(Intent(this, SeedPhraseActivity::class.java))
    }

    private fun openRestoreWallet() {
        startActivity(Intent(this, RestoreWalletActivity::class.java))
    }

    private fun openChangePin() {
        startActivityForResultCompat(
            Intent(this, PinSetupActivity::class.java).apply {
                putExtra(PinSetupActivity.EXTRA_MODE, PinSetupActivity.MODE_CHANGE)
            },
            REQUEST_PIN_SETUP
        )
    }

    private fun confirmRemovePin() {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.security_settings_remove_pin_dialog_title),
                message = getString(R.string.security_settings_remove_pin_dialog_message),
                confirmText = getString(R.string.security_settings_remove_pin_confirm),
                isDestructive = true,
                onConfirm = {
                    pinManager.removePin()
                    updatePinUI()
                }
            )
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_PIN_SETUP -> {
                updatePinUI()
            }
            REQUEST_PIN_VERIFY -> {
                if (resultCode == Activity.RESULT_OK) {
                    // PIN verified - perform pending action
                    when (pendingAction) {
                        PendingAction.BACKUP_MNEMONIC -> openBackupMnemonic()
                        PendingAction.ENABLE_DEVICE_BACKUP -> openDeviceBackupSetup()
                        PendingAction.RESTORE_WALLET -> openRestoreWallet()
                        PendingAction.CHANGE_PIN -> openChangePin()
                        PendingAction.REMOVE_PIN -> confirmRemovePin()
                        null -> {}
                    }
                }
                pendingAction = null
            }
        }
    }

    companion object {
        private const val REQUEST_PIN_SETUP = 1001
        private const val REQUEST_PIN_VERIFY = 1002
    }
}
