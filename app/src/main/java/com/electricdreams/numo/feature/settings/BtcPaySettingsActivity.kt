package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

import com.electricdreams.numo.R
import com.electricdreams.numo.core.payment.BTCPayConfig
import com.electricdreams.numo.core.payment.BtcPayAppsService
import com.electricdreams.numo.core.payment.BtcPayPosApp
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.databinding.ActivityBtcpaySettingsBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

class BtcPaySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBtcpaySettingsBinding

    private lateinit var enableSwitch: MaterialSwitch
    private lateinit var serverUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var storeIdInput: EditText
    private lateinit var testConnectionStatus: TextView
    private lateinit var posSectionLabel: View
    private lateinit var posAppCard: View
    private lateinit var posAppSubtitle: TextView

    private var isTestingConnection = false
    private var isLoadingSettings = false

    companion object {
        private const val KEY_ENABLED = "btcpay_enabled"
        private const val KEY_SERVER_URL = "btcpay_server_url"
        private const val KEY_API_KEY = "btcpay_api_key"
        private const val KEY_STORE_ID = "btcpay_store_id"
        private const val KEY_POS_APP_ID = "btcpay_pos_app_id"
        private const val KEY_POS_APP_NAME = "btcpay_pos_app_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBtcpaySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        initViews()
        setupListeners()
        loadSettings()
    }

    private fun initViews() {
        binding.topBar.onNavClick {
            onBackPressedDispatcher.onBackPressed()
        }

        enableSwitch = binding.btcpayEnableSwitch
        serverUrlInput = binding.btcpayServerUrlInput
        apiKeyInput = binding.btcpayApiKeyInput
        storeIdInput = binding.btcpayStoreIdInput
        testConnectionStatus = binding.testConnectionStatus
        posSectionLabel = binding.posSectionLabel
        posAppCard = binding.posAppCard
        posAppSubtitle = binding.posAppSubtitle
    }

    private fun hasAllFields(): Boolean {
        return serverUrlInput.text.toString().isNotBlank() &&
            apiKeyInput.text.toString().isNotBlank() &&
            storeIdInput.text.toString().isNotBlank()
    }

    private fun updateToggleEnabled() {
        val canEnable = hasAllFields()
        enableSwitch.isEnabled = canEnable
        if (!canEnable && enableSwitch.isChecked) {
            enableSwitch.isChecked = false
            PreferenceStore.app(this).putBoolean(KEY_ENABLED, false)
        }
    }

    private fun updatePosVisibility() {
        val visible = if (enableSwitch.isChecked) View.VISIBLE else View.GONE
        posSectionLabel.visibility = visible
        posAppCard.visibility = visible
    }

    private fun setupListeners() {
        val enableToggleRow = binding.enableToggleRow
        enableToggleRow.setOnClickListener {
            if (hasAllFields()) enableSwitch.performClick()
        }

        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            PreferenceStore.app(this).putBoolean(KEY_ENABLED, isChecked)
            updatePosVisibility()
        }

        val fieldWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isLoadingSettings) return
                updateToggleEnabled()
                // Clear POS selection — it belongs to a different server/store.
                clearPosSelection()
            }
        }
        serverUrlInput.addTextChangedListener(fieldWatcher)
        apiKeyInput.addTextChangedListener(fieldWatcher)
        storeIdInput.addTextChangedListener(fieldWatcher)

        binding.testConnectionRow.setOnClickListener {
            testConnection()
        }

        binding.posAppRow.setOnClickListener {
            showPosAppPicker()
        }
    }

    private fun loadSettings() {
        isLoadingSettings = true
        val prefs = PreferenceStore.app(this)
        serverUrlInput.setText(prefs.getString(KEY_SERVER_URL, "") ?: "")
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, "") ?: "")
        storeIdInput.setText(prefs.getString(KEY_STORE_ID, "") ?: "")
        isLoadingSettings = false

        val alreadyEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val canEnable = hasAllFields()
        enableSwitch.isEnabled = canEnable
        enableSwitch.isChecked = canEnable && alreadyEnabled

        val posAppName = prefs.getString(KEY_POS_APP_NAME, null)
        posAppSubtitle.text = posAppName ?: getString(R.string.btcpay_pos_app_subtitle_none)

        updatePosVisibility()
    }

    private fun saveTextFields() {
        val prefs = PreferenceStore.app(this)
        prefs.putString(KEY_SERVER_URL, serverUrlInput.text.toString().trim())
        prefs.putString(KEY_API_KEY, apiKeyInput.text.toString().trim())
        prefs.putString(KEY_STORE_ID, storeIdInput.text.toString().trim())
    }

    private fun clearPosSelection() {
        val prefs = PreferenceStore.app(this)
        val hadSelection = !prefs.getString(KEY_POS_APP_ID, null).isNullOrBlank()
        prefs.remove(KEY_POS_APP_ID)
        prefs.remove(KEY_POS_APP_NAME)
        if (hadSelection) {
            posAppSubtitle.text = getString(R.string.btcpay_pos_app_subtitle_none)
        }
    }

    private fun showPosAppPicker() {
        val serverUrl = serverUrlInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        val storeId = storeIdInput.text.toString().trim()

        if (serverUrl.isBlank() || apiKey.isBlank() || storeId.isBlank()) {
            Toast.makeText(this, R.string.btcpay_test_fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }

        val config = BTCPayConfig(serverUrl = serverUrl, apiKey = apiKey, storeId = storeId)

        val loadingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.btcpay_pos_select_title)
            .setMessage(R.string.btcpay_test_connecting)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BtcPayAppsService.fetchPosApps(config) }
            }
            loadingDialog.dismiss()

            result.onSuccess { apps ->
                presentPosAppDialog(apps)
            }.onFailure { e ->
                val msg = getString(R.string.btcpay_pos_load_error, e.message ?: "")
                Toast.makeText(this@BtcPaySettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun presentPosAppDialog(apps: List<BtcPayPosApp>) {
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.btcpay_pos_no_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceStore.app(this)
        val currentId = prefs.getString(KEY_POS_APP_ID, null)

        val options = listOf(getString(R.string.btcpay_pos_none_option)) + apps.map { it.name }
        val currentIndex = apps.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it + 1 }
        var selectedIndex = currentIndex

        AlertDialog.Builder(this)
            .setTitle(R.string.btcpay_pos_select_title)
            .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex == 0) {
                    clearPosSelection()
                } else {
                    val app = apps[selectedIndex - 1]
                    prefs.putString(KEY_POS_APP_ID, app.id)
                    prefs.putString(KEY_POS_APP_NAME, app.name)
                    posAppSubtitle.text = app.name
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testConnection() {
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')
        val apiKey = apiKeyInput.text.toString().trim()
        val storeId = storeIdInput.text.toString().trim()

        if (serverUrl.isBlank() || apiKey.isBlank() || storeId.isBlank()) {
            testConnectionStatus.text = getString(R.string.btcpay_test_fill_all_fields)
            testConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_error))
            return
        }
        if (isTestingConnection) return

        isTestingConnection = true
        testConnectionStatus.text = getString(R.string.btcpay_test_connecting)
        testConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("$serverUrl/api/v1/stores/$storeId/invoices")
                        .header("Authorization", "token $apiKey")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val code = response.code
                    response.close()

                    if (code in 200..299) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            isTestingConnection = false
            if (result.isSuccess) {
                testConnectionStatus.text = getString(R.string.btcpay_test_success)
                testConnectionStatus.setTextColor(ContextCompat.getColor(this@BtcPaySettingsActivity, R.color.color_success_green))
            } else {
                val error = result.exceptionOrNull()?.message ?: getString(R.string.btcpay_test_unknown_error)
                testConnectionStatus.text = getString(R.string.btcpay_test_failed, error)
                testConnectionStatus.setTextColor(ContextCompat.getColor(this@BtcPaySettingsActivity, R.color.color_error))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveTextFields()
    }
}
