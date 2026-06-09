package com.electricdreams.numo.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.bark.BarkWalletManager
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BarkSettingsActivity : AppCompatActivity() {

    private lateinit var enableSwitch: SwitchCompat
    private lateinit var balanceText: TextView
    private lateinit var addressText: TextView
    private lateinit var syncButton: Button
    private lateinit var withdrawFundsButton: Button
    private lateinit var serverInput: TextInputEditText
    private lateinit var esploraInput: TextInputEditText
    private lateinit var networkInput: TextInputEditText
    private lateinit var saveConfigButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bark_settings)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        initViews()
        setupListeners()
        loadSettings()
        updateWalletInfo()
    }

    private fun initViews() {
        enableSwitch = findViewById(R.id.bark_enable_switch)
        balanceText = findViewById(R.id.bark_balance_text)
        addressText = findViewById(R.id.bark_address_text)
        syncButton = findViewById(R.id.sync_button)
        withdrawFundsButton = findViewById(R.id.withdraw_funds_button)
        serverInput = findViewById(R.id.bark_server_input)
        esploraInput = findViewById(R.id.bark_esplora_input)
        networkInput = findViewById(R.id.bark_network_input)
        saveConfigButton = findViewById(R.id.save_config_button)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            val prefs = PreferenceStore.app(this)
            prefs.putBoolean("bark_enabled", isChecked)

            if (isChecked) {
                BarkWalletManager.init(this)
                lifecycleScope.launch {
                    updateWalletInfo()
                }
            } else {
                BarkWalletManager.disableWallet()
                balanceText.text = "0 sats"
                addressText.text = "-"
            }
        }

        syncButton.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@BarkSettingsActivity, "Syncing Bark Wallet...", Toast.LENGTH_SHORT).show()
                BarkWalletManager.runSyncAndMaintenance()
                updateWalletInfo()
                Toast.makeText(this@BarkSettingsActivity, "Bark Wallet Synced!", Toast.LENGTH_SHORT).show()
            }
        }

        withdrawFundsButton.setOnClickListener {
            if (!enableSwitch.isChecked) {
                Toast.makeText(this, "Please enable Bark Wallet first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, BarkWithdrawActivity::class.java))
        }

        saveConfigButton.setOnClickListener {
            val server = serverInput.text?.toString()?.trim() ?: ""
            val esplora = esploraInput.text?.toString()?.trim() ?: ""
            val network = networkInput.text?.toString()?.trim()?.uppercase() ?: ""

            if (server.isBlank() || esplora.isBlank() || network.isBlank()) {
                Toast.makeText(this, "Fields cannot be blank", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = PreferenceStore.app(this)
            prefs.putString("bark_server_url", server)
            prefs.putString("bark_esplora_url", esplora)
            prefs.putString("bark_network", network)

            // Re-initialize Bark Wallet
            BarkWalletManager.disableWallet()
            if (enableSwitch.isChecked) {
                lifecycleScope.launch {
                    updateWalletInfo()
                }
            }

            Toast.makeText(this, "Connection configuration saved!", Toast.LENGTH_SHORT).show()
        }

        addressText.setOnClickListener {
            val address = addressText.text.toString()
            if (address.isNotBlank() && address != "-") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Ark Address", address)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Address copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceStore.app(this)
        enableSwitch.isChecked = prefs.getBoolean("bark_enabled", false)
        val network = prefs.getString("bark_network") ?: "SIGNET"
        networkInput.setText(network)

        val defaultServer = if (network.uppercase() == "MAINNET") "https://ark.second.tech" else "https://ark.signet.2nd.dev"
        val defaultEsplora = if (network.uppercase() == "MAINNET") "https://mempool.second.tech/api" else "https://esplora.signet.2nd.dev"

        serverInput.setText(prefs.getString("bark_server_url")?.takeIf { it.isNotBlank() } ?: defaultServer)
        esploraInput.setText(prefs.getString("bark_esplora_url")?.takeIf { it.isNotBlank() } ?: defaultEsplora)

        // Add dynamically updated hints / pre-fill on network input change
        networkInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val currentNet = s?.toString()?.trim()?.uppercase() ?: ""
                val defSrv = if (currentNet == "MAINNET") "https://ark.second.tech" else "https://ark.signet.2nd.dev"
                val defEsp = if (currentNet == "MAINNET") "https://mempool.second.tech/api" else "https://esplora.signet.2nd.dev"
                
                // Only auto-update if the user hasn't typed a completely custom URL
                val currentSrv = serverInput.text?.toString()?.trim() ?: ""
                if (currentSrv == "https://ark.signet.2nd.dev" || currentSrv == "https://ark.second.tech" || currentSrv.isBlank()) {
                    serverInput.setText(defSrv)
                }
                val currentEsp = esploraInput.text?.toString()?.trim() ?: ""
                if (currentEsp == "https://esplora.signet.2nd.dev" || currentEsp == "https://mempool.second.tech/api" || currentEsp.isBlank()) {
                    esploraInput.setText(defEsp)
                }
            }
        })
    }

    private fun updateWalletInfo() {
        if (!enableSwitch.isChecked) {
            balanceText.text = "Disabled"
            addressText.text = "-"
            return
        }

        lifecycleScope.launch {
            balanceText.text = "Loading..."
            addressText.text = "Loading..."
            try {
                val wallet = BarkWalletManager.getWallet()
                if (wallet != null) {
                    val balance = wallet.balance().spendableSats.toLong()
                    val address = wallet.newAddress()
                    balanceText.text = "$balance sats"
                    addressText.text = address
                } else {
                    balanceText.text = "Error"
                    addressText.text = "Could not initialize wallet"
                }
            } catch (e: Exception) {
                Log.e("BarkSettings", "Failed to update wallet info: ${e.message}", e)
                balanceText.text = "Error"
                addressText.text = e.message ?: "Failed to load"
            }
        }
    }
}
