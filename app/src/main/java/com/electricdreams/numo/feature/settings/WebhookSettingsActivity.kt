package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.WebhookSettingsManager
import com.electricdreams.numo.ui.util.DialogHelper

/**
 * Settings screen for configuring payment-received webhooks.
 */
class WebhookSettingsActivity : AppCompatActivity() {

    private lateinit var webhookSettingsManager: WebhookSettingsManager
    private lateinit var endpointsList: LinearLayout
    private lateinit var emptyStateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webhook_settings)

        webhookSettingsManager = WebhookSettingsManager.getInstance(this)

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.add_endpoint_button).setOnClickListener { showAddEndpointDialog() }

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
            val deleteButton = item.findViewById<ImageButton>(R.id.delete_button)

            endpointText.text = endpoint
            item.setOnClickListener {
                showEditEndpointDialog(endpoint)
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

    private fun showAddEndpointDialog() {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.webhook_settings_add_dialog_title),
                description = getString(R.string.webhook_settings_add_dialog_description),
                hint = getString(R.string.webhook_settings_add_dialog_hint),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                saveText = getString(R.string.webhook_settings_add_dialog_confirm),
                onSave = { rawUrl ->
                    when (webhookSettingsManager.addEndpoint(rawUrl)) {
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
                validator = { value ->
                    webhookSettingsManager.isValidEndpoint(value)
                },
            ),
        )
    }

    private fun showEditEndpointDialog(currentEndpoint: String) {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.webhook_settings_edit_dialog_title),
                description = getString(R.string.webhook_settings_edit_dialog_description),
                initialValue = currentEndpoint,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                saveText = getString(R.string.common_save),
                onSave = { updatedUrl ->
                    when (webhookSettingsManager.updateEndpoint(currentEndpoint, updatedUrl)) {
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

    private fun showDeleteConfirmation(endpoint: String) {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.webhook_settings_delete_dialog_title),
                message = getString(R.string.webhook_settings_delete_dialog_message, endpoint),
                confirmText = getString(R.string.webhook_settings_delete_dialog_confirm),
                cancelText = getString(R.string.common_cancel),
                isDestructive = true,
                onConfirm = {
                    if (webhookSettingsManager.removeEndpoint(endpoint)) {
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
}
