package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.WebhookSettingsManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.scanner.QRScannerActivity
import com.electricdreams.numo.payment.PaymentWebhookDispatcher
import com.electricdreams.numo.ui.util.DialogHelper
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.widget.ImageView
import java.util.concurrent.TimeUnit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Settings screen for configuring payment-received webhooks.
 */
class WebhookSettingsActivity : AppCompatActivity() {

    private lateinit var webhookSettingsManager: WebhookSettingsManager
    private lateinit var endpointsList: LinearLayout
    private lateinit var emptyStateText: TextView
    private lateinit var qrScannerLauncher: ActivityResultLauncher<Intent>
    private var currentDialog: AlertDialog? = null
    private var isSyncing: Boolean = false
    private var syncJob: Job? = null

    private val pingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webhook_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webhookSettingsManager = WebhookSettingsManager.getInstance(this)

        qrScannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val qrValue = result.data?.getStringExtra(QRScannerActivity.EXTRA_QR_VALUE)
                if (!qrValue.isNullOrBlank()) {
                    handleScannedQr(qrValue)
                }
            }
        }

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.add_endpoint_button).setOnClickListener { showAddEndpointDialog() }
        findViewById<View>(R.id.sync_all_button).setOnClickListener { syncAllTransactions() }

        endpointsList = findViewById(R.id.endpoints_list)
        emptyStateText = findViewById(R.id.empty_state_text)

        refreshEndpoints()
    }

    private fun refreshEndpoints() {
        endpointsList.removeAllViews()

        val endpoints = webhookSettingsManager.getEndpoints()
        emptyStateText.visibility = if (endpoints.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        endpoints.forEachIndexed { index, endpoint ->
            val item = inflater.inflate(R.layout.item_webhook_endpoint, endpointsList, false)
            val endpointText = item.findViewById<TextView>(R.id.endpoint_url_text)
            val authStatusText = item.findViewById<TextView>(R.id.endpoint_auth_status_text)
            val statusDot = item.findViewById<ImageView>(R.id.endpoint_status_dot)
            val editAuthButton = item.findViewById<ImageButton>(R.id.edit_auth_button)
            val deleteButton = item.findViewById<ImageButton>(R.id.delete_button)

            endpointText.text = endpoint.url
            authStatusText.text = if (endpoint.hasAuthKey()) {
                getString(R.string.webhook_settings_auth_set)
            } else {
                getString(R.string.webhook_settings_auth_not_set)
            }

            statusDot.setImageResource(R.drawable.bg_status_dot_gray)
            lifecycleScope.launch(Dispatchers.IO) {
                var isReachable = false
                try {
                    val healthUrl = java.net.URL(java.net.URL(endpoint.url), "/api/health").toString()
                    val request = Request.Builder()
                        .url(healthUrl)
                        .get()
                        .build()
                    pingClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            isReachable = bodyStr?.contains("\"ok\":true") == true || bodyStr?.contains("\"ok\": true") == true
                        }
                    }
                } catch (e: Exception) {
                    isReachable = false
                }

                withContext(Dispatchers.Main) {
                    if (isReachable) {
                        statusDot.setImageResource(R.drawable.bg_status_dot_green)
                    } else {
                        statusDot.setImageResource(R.drawable.bg_status_dot_red)
                    }
                }
            }

            item.setOnClickListener {
                showEditEndpointDialog(endpoint)
            }
            editAuthButton.setOnClickListener {
                showEditAuthKeyDialog(endpoint)
            }
            deleteButton.setOnClickListener {
                showDeleteConfirmation(endpoint)
            }

            endpointsList.addView(item)
            addDividerIfNeeded(endpointsList, index < endpoints.lastIndex)
        }
    }

    private fun addDividerIfNeeded(container: LinearLayout, shouldAdd: Boolean) {
        if (!shouldAdd) {
            return
        }

        val divider = View(this).apply {
            val dividerHeightPx = maxOf(1, (0.5f * resources.displayMetrics.density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dividerHeightPx,
            ).apply {
                marginStart = (52 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(resources.getColor(R.color.color_divider, theme))
        }
        container.addView(divider)
    }

    private fun syncAllTransactions() {
        if (isSyncing) return
        
        if (webhookSettingsManager.getEndpoints().isEmpty()) {
            Toast.makeText(this, R.string.webhook_settings_empty, Toast.LENGTH_SHORT).show()
            return
        }

        isSyncing = true
        val syncButton = findViewById<View>(R.id.sync_all_button)
        syncButton.alpha = 0.5f

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sync_progress, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.sync_progress_bar)
        val progressMessage = dialogView.findViewById<TextView>(R.id.sync_progress_message)
        
        progressBar.isIndeterminate = true
        progressMessage.text = getString(R.string.webhook_settings_syncing_progress, 0, 0)
        
        val progressDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                syncJob?.cancel()
                isSyncing = false
                syncButton.alpha = 1.0f
                Toast.makeText(this, R.string.webhook_settings_sync_cancelled, Toast.LENGTH_SHORT).show()
            }
            .create()
            
        progressDialog.show()

        syncJob = lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    PaymentsHistoryActivity.getPaymentHistory(this@WebhookSettingsActivity)
                }

                val completedTransactions = history.filter { it.isCompleted() }

                if (completedTransactions.isEmpty()) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this@WebhookSettingsActivity,
                        R.string.webhook_settings_sync_all_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                
                progressMessage.text = getString(
                    R.string.webhook_settings_syncing_progress, 
                    completedTransactions.size, 
                    completedTransactions.size
                )
                progressBar.isIndeterminate = false
                progressBar.max = completedTransactions.size
                progressBar.progress = completedTransactions.size

                var successCount = 0
                val dispatcher = PaymentWebhookDispatcher.getInstance(this@WebhookSettingsActivity)

                val result = dispatcher.dispatchBulkPaymentsNow(completedTransactions)
                successCount = result.successCount

                progressDialog.dismiss()
                Toast.makeText(
                    this@WebhookSettingsActivity,
                    getString(R.string.webhook_settings_sync_all_success, successCount),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error during bulk sync: ${e.message}", e)
                progressDialog.dismiss()
                Toast.makeText(
                    this@WebhookSettingsActivity,
                    "Sync failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isSyncing = false
                syncButton.alpha = 1.0f
                syncJob = null
            }
        }
    }

    private fun handleScannedQr(qrValue: String) {
        val gson = Gson()
        try {
            // Try to parse as JSON first
            val payload = gson.fromJson(qrValue, WebhookQrPayload::class.java)
            val url = payload?.url
            if (payload != null && !url.isNullOrBlank()) {
                // Auto-add
                when (webhookSettingsManager.addEndpoint(url, payload.token)) {
                    WebhookSettingsManager.SaveResult.SUCCESS -> {
                        refreshEndpoints()
                        Toast.makeText(
                            this,
                            getString(R.string.webhook_settings_add_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                        currentDialog?.dismiss()
                    }
                    WebhookSettingsManager.SaveResult.DUPLICATE -> {
                        Toast.makeText(
                            this,
                            getString(R.string.webhook_settings_error_duplicate),
                            Toast.LENGTH_LONG,
                        ).show()
                        populateDialogInput(url)
                    }
                    else -> {
                        // Invalid URL in JSON or other error
                        populateDialogInput(url)
                    }
                }
            } else {
                // Fallback to simple string
                populateDialogInput(qrValue)
            }
        } catch (e: Exception) {
            // Not JSON, assume raw URL
            populateDialogInput(qrValue)
        }
    }

    private fun populateDialogInput(value: String?) {
        if (value.isNullOrBlank()) return
        currentDialog?.findViewById<EditText>(R.id.dialog_input)?.let { editText ->
            editText.setText(value)
            editText.setSelection(editText.text.length)
        }
    }

    private fun showAddEndpointDialog() {
        currentDialog = DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.webhook_settings_add_dialog_title),
                description = getString(R.string.webhook_settings_add_dialog_description),
                hint = getString(R.string.webhook_settings_add_dialog_hint),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                saveText = getString(R.string.webhook_settings_dialog_continue),
                onSave = { rawUrl ->
                    showAddAuthKeyDialog(rawUrl)
                },
                validator = { value ->
                    webhookSettingsManager.isValidEndpoint(value)
                },
                onScan = {
                    val intent = Intent(this, QRScannerActivity::class.java).apply {
                        putExtra(
                            QRScannerActivity.EXTRA_TITLE,
                            getString(R.string.webhook_settings_scan_title)
                        )
                        putExtra(
                            QRScannerActivity.EXTRA_INSTRUCTION,
                            getString(R.string.webhook_settings_scan_instruction)
                        )
                    }
                    qrScannerLauncher.launch(intent)
                }
            ),
        )
    }

    private data class WebhookQrPayload(
        val url: String?,
        val token: String?
    )

    private fun showAddAuthKeyDialog(rawUrl: String) {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.webhook_settings_auth_dialog_title),
                description = getString(R.string.webhook_settings_auth_dialog_description),
                hint = getString(R.string.webhook_settings_auth_dialog_hint),
                helperText = getString(R.string.webhook_settings_auth_dialog_helper),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                saveText = getString(R.string.webhook_settings_add_dialog_confirm),
                onSave = { authKey ->
                    when (webhookSettingsManager.addEndpoint(rawUrl, authKey)) {
                        WebhookSettingsManager.SaveResult.SUCCESS -> {
                            refreshEndpoints()
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_add_success),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.DUPLICATE -> {
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_duplicate),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.INVALID_URL,
                        WebhookSettingsManager.SaveResult.NOT_FOUND -> {
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_invalid_url),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            ),
        )
    }

    private fun showEditEndpointDialog(endpoint: WebhookSettingsManager.WebhookEndpointConfig) {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.webhook_settings_edit_dialog_title),
                description = getString(R.string.webhook_settings_edit_dialog_description),
                initialValue = endpoint.url,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                saveText = getString(R.string.common_save),
                onSave = { updatedUrl ->
                    when (
                        webhookSettingsManager.updateEndpoint(
                            currentEndpoint = endpoint.url,
                            newRawUrl = updatedUrl,
                            newRawAuthKey = endpoint.authKey,
                        )
                    ) {
                        WebhookSettingsManager.SaveResult.SUCCESS -> {
                            refreshEndpoints()
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_edit_success),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.DUPLICATE -> {
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_duplicate),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.INVALID_URL -> {
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_invalid_url),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.NOT_FOUND -> {
                            refreshEndpoints()
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_not_found),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                validator = { value ->
                    webhookSettingsManager.isValidEndpoint(value)
                },
            ),
        )
    }

    private fun showEditAuthKeyDialog(endpoint: WebhookSettingsManager.WebhookEndpointConfig) {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.webhook_settings_auth_dialog_title),
                description = getString(R.string.webhook_settings_auth_dialog_description),
                hint = getString(R.string.webhook_settings_auth_dialog_hint),
                initialValue = endpoint.authKey.orEmpty(),
                helperText = getString(R.string.webhook_settings_auth_dialog_helper),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                saveText = getString(R.string.common_save),
                onSave = { authKey ->
                    when (webhookSettingsManager.updateAuthKey(endpoint.url, authKey)) {
                        WebhookSettingsManager.SaveResult.SUCCESS -> {
                            refreshEndpoints()
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_auth_success),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.NOT_FOUND -> {
                            refreshEndpoints()
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_not_found),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        WebhookSettingsManager.SaveResult.INVALID_URL,
                        WebhookSettingsManager.SaveResult.DUPLICATE -> {
                            Toast.makeText(
                                this,
                                getString(R.string.webhook_settings_error_invalid_url),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            ),
        )
    }

    private fun showDeleteConfirmation(endpoint: WebhookSettingsManager.WebhookEndpointConfig) {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.webhook_settings_delete_dialog_title),
                message = getString(R.string.webhook_settings_delete_dialog_message, endpoint.url),
                confirmText = getString(R.string.webhook_settings_delete_dialog_confirm),
                cancelText = getString(R.string.common_cancel),
                isDestructive = true,
                onConfirm = {
                    if (webhookSettingsManager.removeEndpoint(endpoint.url)) {
                        refreshEndpoints()
                        Toast.makeText(
                            this,
                            getString(R.string.webhook_settings_delete_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ),
        )
    }

    companion object {
        private const val TAG = "WebhookSettingsActivity"
    }
}
