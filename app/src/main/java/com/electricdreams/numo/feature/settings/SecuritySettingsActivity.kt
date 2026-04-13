package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.electricdreams.numo.util.startActivityForResultCompat
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinSetupActivity
import com.electricdreams.numo.ui.util.DialogHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager

    private lateinit var setupPinItem: View
    private lateinit var changePinItem: View
    private lateinit var removePinItem: View

    private var pendingAction: PendingAction? = null

    private enum class PendingAction {
        BACKUP_MNEMONIC,
        RESTORE_WALLET,
        CHANGE_PIN,
        REMOVE_PIN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        pinManager = PinManager.getInstance(this)

        setupPinItem = findViewById(R.id.setup_pin_item)
        changePinItem = findViewById(R.id.change_pin_item)
        removePinItem = findViewById(R.id.remove_pin_item)

        // Back button
        findViewById<View>(R.id.back_button).setOnClickListener {
            finish()
        }

        // Title
        findViewById<TextView>(R.id.toolbar_title).text = getString(R.string.settings_security_title)

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
        findViewById<View>(R.id.backup_mnemonic_item).setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.BACKUP_MNEMONIC
                requestPinVerification()
            } else {
                openBackupMnemonic()
            }
        }

        // Restore wallet - requires PIN if set
        findViewById<View>(R.id.restore_wallet_item).setOnClickListener {
            if (pinManager.isPinEnabled()) {
                pendingAction = PendingAction.RESTORE_WALLET
                requestPinVerification()
            } else {
                openRestoreWallet()
            }
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
                cancelText = getString(R.string.common_cancel),
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
