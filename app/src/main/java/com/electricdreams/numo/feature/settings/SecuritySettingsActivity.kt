package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinSetupActivity

/**
 * Security & Privacy settings screen.
 * Provides access to:
 * - PIN Protection: Set/change/remove PIN for sensitive features
 * - Backup Mnemonic: View and copy the 12-word seed phrase
 * - Restore Wallet: Recover wallet from an existing seed phrase
 */
class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    
    private lateinit var setupPinItem: View
    private lateinit var changePinItem: View
    private lateinit var removePinItem: View
    private lateinit var pinTitle: TextView
    private lateinit var pinSubtitle: TextView

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

        pinManager = PinManager.getInstance(this)
        initViews()
        setupListeners()
        updatePinUI()
    }

    override fun onResume() {
        super.onResume()
        updatePinUI()
    }

    private fun initViews() {
        setupPinItem = findViewById(R.id.setup_pin_item)
        changePinItem = findViewById(R.id.change_pin_item)
        removePinItem = findViewById(R.id.remove_pin_item)
        pinTitle = findViewById(R.id.pin_title)
        pinSubtitle = findViewById(R.id.pin_subtitle)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.back_button).setOnClickListener { 
            finish() 
        }

        // PIN setup/change items
        setupPinItem.setOnClickListener {
            startActivityForResult(
                Intent(this, PinSetupActivity::class.java).apply {
                    putExtra(PinSetupActivity.EXTRA_MODE, PinSetupActivity.MODE_CREATE)
                },
                REQUEST_PIN_SETUP
            )
        }

        changePinItem.setOnClickListener {
            pendingAction = PendingAction.CHANGE_PIN
            requestPinVerification()
        }

        removePinItem.setOnClickListener {
            pendingAction = PendingAction.REMOVE_PIN
            requestPinVerification()
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
            putExtra(PinEntryActivity.EXTRA_TITLE, "Enter PIN")
            putExtra(PinEntryActivity.EXTRA_SUBTITLE, "Verify your identity to continue")
        }
        startActivityForResult(intent, REQUEST_PIN_VERIFY)
    }

    private fun openBackupMnemonic() {
        startActivity(Intent(this, SeedPhraseActivity::class.java))
    }

    private fun openRestoreWallet() {
        startActivity(Intent(this, RestoreWalletActivity::class.java))
    }

    private fun openChangePin() {
        startActivityForResult(
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
                title = "Remove PIN?",
                message = "This will disable PIN protection. Anyone with access to this device will be able to access sensitive features like withdrawing funds and viewing your seed phrase.",
                confirmText = "Remove",
                cancelText = "Cancel",
                isDestructive = true,
                onConfirm = {
                    pinManager.removePin()
                    updatePinUI()
                }
            )
        )
    }

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
