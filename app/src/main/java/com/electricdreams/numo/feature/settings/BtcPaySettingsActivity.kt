package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BtcPaySettingsActivity : AppCompatActivity() {

    private lateinit var enableSwitch: SwitchCompat
    private lateinit var serverUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var storeIdInput: EditText
    private lateinit var testConnectionStatus: TextView

    private var connectionTestPassed = false

    companion object {
        private const val KEY_ENABLED = "btcpay_enabled"
        private const val KEY_SERVER_URL = "btcpay_server_url"
        private const val KEY_API_KEY = "btcpay_api_key"
        private const val KEY_STORE_ID = "btcpay_store_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_btcpay_settings)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        initViews()
        setupListeners()
        loadSettings()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        enableSwitch = findViewById(R.id.btcpay_enable_switch)
        serverUrlInput = findViewById(R.id.btcpay_server_url_input)
        apiKeyInput = findViewById(R.id.btcpay_api_key_input)
        storeIdInput = findViewById(R.id.btcpay_store_id_input)
        testConnectionStatus = findViewById(R.id.test_connection_status)
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

    private fun setupListeners() {
        val enableToggleRow = findViewById<LinearLayout>(R.id.enable_toggle_row)
        enableToggleRow.setOnClickListener {
            if (hasAllFields()) enableSwitch.toggle()
        }

        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !connectionTestPassed) {
                enableSwitch.isChecked = false
                testConnection(onSuccess = { enableSwitch.isChecked = true })
            } else {
                PreferenceStore.app(this).putBoolean(KEY_ENABLED, isChecked)
            }
        }

        val fieldWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                connectionTestPassed = false
                updateToggleEnabled()
            }
        }
        serverUrlInput.addTextChangedListener(fieldWatcher)
        apiKeyInput.addTextChangedListener(fieldWatcher)
        storeIdInput.addTextChangedListener(fieldWatcher)

        findViewById<LinearLayout>(R.id.test_connection_row).setOnClickListener {
            testConnection()
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceStore.app(this)
        serverUrlInput.setText(prefs.getString(KEY_SERVER_URL, "") ?: "")
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, "") ?: "")
        storeIdInput.setText(prefs.getString(KEY_STORE_ID, "") ?: "")
        val alreadyEnabled = prefs.getBoolean(KEY_ENABLED, false)
        if (alreadyEnabled && hasAllFields()) connectionTestPassed = true
        val canEnable = hasAllFields() && connectionTestPassed
        enableSwitch.isEnabled = canEnable
        enableSwitch.isChecked = canEnable && alreadyEnabled
    }

    private fun saveTextFields() {
        val prefs = PreferenceStore.app(this)
        prefs.putString(KEY_SERVER_URL, serverUrlInput.text.toString().trim())
        prefs.putString(KEY_API_KEY, apiKeyInput.text.toString().trim())
        prefs.putString(KEY_STORE_ID, storeIdInput.text.toString().trim())
    }

    private fun testConnection(onSuccess: (() -> Unit)? = null) {
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')
        val apiKey = apiKeyInput.text.toString().trim()
        val storeId = storeIdInput.text.toString().trim()

        if (serverUrl.isBlank() || apiKey.isBlank() || storeId.isBlank()) {
            testConnectionStatus.text = getString(R.string.btcpay_test_fill_all_fields)
            testConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_error))
            return
        }

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

            if (result.isSuccess) {
                connectionTestPassed = true
                updateToggleEnabled()
                onSuccess?.invoke()
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
