package com.electricdreams.numo.feature.autowithdraw

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.shake
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated settings screen for automatic withdrawals: the enable toggle,
 * the destination lightning address (editable even while disabled, since a
 * valid address is required before enabling), and the trigger settings.
 *
 * Manual withdrawals and recent activity live on the Withdraw hub
 * ([com.electricdreams.numo.feature.withdraw.WithdrawHubActivity]).
 */
class AutoWithdrawSettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: AutoWithdrawSettingsManager

    private lateinit var enableSwitch: SwitchCompat
    private lateinit var enableToggleRow: LinearLayout
    private lateinit var lightningAddressInput: EditText
    private lateinit var lightningAddressValidation: TextView
    private lateinit var thresholdRow: LinearLayout
    private lateinit var thresholdDisplay: TextView
    private lateinit var percentageSlider: Slider
    private lateinit var percentageBadge: TextView

    // Trigger-settings container (hidden while auto-withdraw is disabled)
    private lateinit var configContainer: LinearLayout

    private var isUpdatingUI = false

    // Current threshold value (in sats)
    private var currentThreshold: Long = AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS
    // Min threshold fetched from LNURL
    private var fetchedMinThresholdSats: Long = AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_withdraw_settings)

        // Global helper: draw content under system bars so nav pill floats over cards
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        settingsManager = AutoWithdrawSettingsManager.getInstance(this)

        initViews()
        setupListeners()
        loadSettings()
    }

    private fun initViews() {
        findViewById<com.electricdreams.numo.ui.components.NumoTopBar>(R.id.top_bar).onNavClick {
            onBackPressedDispatcher.onBackPressed()
        }

        enableSwitch = findViewById(R.id.enable_switch)
        enableToggleRow = findViewById(R.id.enable_toggle_row)

        lightningAddressInput = findViewById(R.id.lightning_address_input)
        lightningAddressValidation = findViewById(R.id.lightning_address_validation)
        thresholdRow = findViewById(R.id.threshold_row)
        thresholdDisplay = findViewById(R.id.threshold_display)
        percentageSlider = findViewById(R.id.percentage_slider)
        percentageBadge = findViewById(R.id.percentage_badge)

        configContainer = findViewById(R.id.auto_withdraw_config_container)
    }

    private fun setupListeners() {
        // Toggle row click (toggles switch)
        enableToggleRow.setOnClickListener {
            enableSwitch.toggle()
        }

        // Enable switch — requires a valid lightning address before enabling
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener

            val address = lightningAddressInput.text.toString().trim()
            val hasValidAddress = LightningAddressManager.getInstance(this).isValidLightningAddress(address)
            if (isChecked && !hasValidAddress) {
                // Revert the switch and point the user at the address field
                isUpdatingUI = true
                enableSwitch.isChecked = false
                isUpdatingUI = false
                lightningAddressInput.shake()
                lightningAddressValidation.visibility = View.VISIBLE
                lightningAddressValidation.text = getString(R.string.auto_withdraw_enable_requires_address)
                lightningAddressValidation.setTextColor(ContextCompat.getColor(this, R.color.color_error))
                return@setOnCheckedChangeListener
            }

            settingsManager.setGloballyEnabled(isChecked)
            animateConfigContainer(isChecked)
        }

        // Lightning address
        lightningAddressInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val address = s?.toString()?.trim() ?: ""
                val isValidFormat = LightningAddressManager.getInstance(this@AutoWithdrawSettingsActivity).isValidLightningAddress(address)

                if (address.isBlank()) {
                    lightningAddressValidation.visibility = View.GONE
                } else if (!isValidFormat) {
                    lightningAddressValidation.visibility = View.VISIBLE
                    lightningAddressValidation.text = getString(R.string.auto_withdraw_lightning_address_invalid)
                    lightningAddressValidation.setTextColor(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_error))
                } else {
                    // Valid format, start network ping
                    if (!isUpdatingUI) {
                        fetchMinThreshold(address)
                    }
                }

                if (!isUpdatingUI) {
                    settingsManager.setDefaultLightningAddress(address)
                }
            }
        })

        // Threshold row - click to show edit sheet
        thresholdRow.setOnClickListener { showThresholdEditDialog() }
        thresholdDisplay.setOnClickListener { showThresholdEditDialog() }

        // Percentage slider with haptic feedback
        percentageSlider.addOnChangeListener { slider, value, fromUser ->
            val percentage = value.toInt()
            percentageBadge.text = "$percentage%"

            if (fromUser && !isUpdatingUI) {
                settingsManager.setDefaultPercentage(percentage)
                // Subtle haptic on step changes
                slider.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)

                // Recalculate minimum threshold based on new percentage
                val address = lightningAddressInput.text.toString().trim()
                if (LightningAddressManager.getInstance(this@AutoWithdrawSettingsActivity).isValidLightningAddress(address)) {
                    fetchMinThreshold(address)
                }
            }
        }
    }

    private fun showThresholdEditDialog() {
        val minAmount = Amount(fetchedMinThresholdSats, Amount.Currency.BTC)
        val dynamicHelperText = getString(R.string.auto_withdraw_threshold_helper_dynamic, minAmount.toString())

        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.auto_withdraw_threshold_title),
                description = getString(R.string.auto_withdraw_threshold_subtitle),
                hint = "50000",
                initialValue = currentThreshold.toString(),
                suffix = "₿",
                helperText = dynamicHelperText,
                inputType = android.text.InputType.TYPE_CLASS_NUMBER,
                saveText = getString(R.string.common_save),
                onSave = { value ->
                    val newThreshold = value.replace(",", "").toLongOrNull()
                        ?: AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS
                    currentThreshold = newThreshold.coerceIn(
                        fetchedMinThresholdSats,
                        AutoWithdrawSettingsManager.MAX_THRESHOLD_SATS
                    )
                    settingsManager.setDefaultThreshold(currentThreshold)
                    updateThresholdDisplay()
                },
                validator = { value ->
                    val amount = value.replace(",", "").toLongOrNull()
                    amount != null && amount >= fetchedMinThresholdSats
                        && amount <= AutoWithdrawSettingsManager.MAX_THRESHOLD_SATS
                }
            )
        )
    }

    private fun updateThresholdDisplay() {
        // Use Amount class to format with ₿ symbol
        val amount = Amount(currentThreshold, Amount.Currency.BTC)
        thresholdDisplay.text = amount.toString()
    }

    private fun loadSettings() {
        isUpdatingUI = true

        val enabled = settingsManager.isGloballyEnabled()
        enableSwitch.isChecked = enabled
        configContainer.visibility = if (enabled) View.VISIBLE else View.GONE

        lightningAddressInput.setText(settingsManager.getDefaultLightningAddress())
        val savedAddress = settingsManager.getDefaultLightningAddress()

        if (LightningAddressManager.getInstance(this).isValidLightningAddress(savedAddress)) {
            fetchMinThreshold(savedAddress)
        } else if (savedAddress.isNotBlank()) {
            lightningAddressValidation.visibility = View.VISIBLE
            lightningAddressValidation.text = getString(R.string.auto_withdraw_lightning_address_invalid)
            lightningAddressValidation.setTextColor(ContextCompat.getColor(this, R.color.color_error))
        }

        currentThreshold = settingsManager.getDefaultThreshold()
        updateThresholdDisplay()

        val percentage = settingsManager.getDefaultPercentage()
        percentageSlider.value = percentage.toFloat()
        percentageBadge.text = "$percentage%"

        isUpdatingUI = false
    }

    private fun fetchMinThreshold(address: String) {
        // Show checking state
        lightningAddressValidation.visibility = View.VISIBLE
        lightningAddressValidation.text = getString(R.string.auto_withdraw_lightning_address_checking)
        lightningAddressValidation.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))

        val percentage = settingsManager.getDefaultPercentage()

        lifecycleScope.launch(Dispatchers.IO) {
            val details = com.electricdreams.numo.core.util.LnUrlClient.fetchLnUrlDetails(address)
            withContext(Dispatchers.Main) {
                if (details != null) {
                    // Update validation UI
                    lightningAddressValidation.text = getString(R.string.auto_withdraw_lightning_address_valid)
                    lightningAddressValidation.setTextColor(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_success_green))

                    // convert msat to sat
                    val minSendableSats = details.minSendable / 1000

                    // Min threshold = minSendableSats * 100 / percentage
                    fetchedMinThresholdSats = (minSendableSats * 100 / percentage) + 1 // +1 to ensure it's strictly > min

                    // Ensure threshold is at least the min
                    if (currentThreshold < fetchedMinThresholdSats) {
                        currentThreshold = fetchedMinThresholdSats
                        settingsManager.setDefaultThreshold(currentThreshold)
                        updateThresholdDisplay()
                    }
                } else {
                    // Update validation UI
                    lightningAddressValidation.text = getString(R.string.auto_withdraw_lightning_address_invalid)
                    lightningAddressValidation.setTextColor(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_error))

                    fetchedMinThresholdSats = AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS
                }
            }
        }
    }

    private fun animateConfigContainer(show: Boolean) {
        if (show) {
            configContainer.visibility = View.VISIBLE
            configContainer.alpha = 0f
            configContainer.translationY = -20f
            configContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            configContainer.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    configContainer.visibility = View.GONE
                }
                .start()
        }
    }
}
