package com.electricdreams.numo.feature.autowithdraw

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.slider.Slider

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.util.LnUrlClient
import com.electricdreams.numo.databinding.ActivityAutoWithdrawSettingsBinding
import com.electricdreams.numo.feature.settings.WithdrawUi
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Auto-withdraw rule: where takings go and when. The Withdraw hub links here and
 * summarises the rule; manual withdrawals and history live on the hub.
 */
class AutoWithdrawSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutoWithdrawSettingsBinding
    private lateinit var settingsManager: AutoWithdrawSettingsManager
    private lateinit var lightningAddressManager: LightningAddressManager

    private var isUpdatingUI = false
    private val lnUrlClient = LnUrlClient
    private var thresholdFetchJob: Job? = null
    private var currentThreshold: Long = AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS
    private var fetchedMinThresholdSats: Long = AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoWithdrawSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        settingsManager = AutoWithdrawSettingsManager.getInstance(this)
        lightningAddressManager = LightningAddressManager.getInstance(this)

        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        setupToggleRow()
        setupListeners()
        loadSettings()
    }

    private fun setupToggleRow() {
        binding.enableToggleRow.setOnClickListener { binding.enableSwitch.toggle() }
        // The row is the control; expose it to TalkBack as the switch it wraps.
        ViewCompat.setAccessibilityDelegate(binding.enableToggleRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = binding.enableSwitch.isChecked
            }
        })
    }

    private fun setupListeners() {
        binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            settingsManager.setGloballyEnabled(isChecked)
            WithdrawUi.animateLayoutChange(binding.content)
            binding.autoWithdrawConfigContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.lightningAddressInput.doAfterTextChanged { text ->
            // A response for the previous address must not overwrite this input's state.
            thresholdFetchJob?.cancel()
            fetchedMinThresholdSats = AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS
            val address = text?.toString()?.trim().orEmpty()

            when {
                address.isBlank() -> showAddressHelper(null)
                !lightningAddressManager.isValidLightningAddress(address) ->
                    binding.lightningAddressLayout.error = getString(R.string.auto_withdraw_lightning_address_invalid)
                !isUpdatingUI -> fetchMinThreshold(address)
            }
            if (!isUpdatingUI) settingsManager.setDefaultLightningAddress(address)
        }

        binding.thresholdRow.setOnClickListener { showThresholdEditDialog() }

        binding.percentageSlider.addOnChangeListener { slider, value, fromUser ->
            val percentage = value.toInt()
            binding.percentageBadge.text = getString(R.string.auto_withdraw_percentage_value, percentage)
            if (fromUser && !isUpdatingUI) {
                settingsManager.setDefaultPercentage(percentage)
                slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        binding.percentageSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                // The minimum threshold depends on the share being sent; check it once the
                // merchant lets go rather than on every step of the drag.
                val address = binding.lightningAddressInput.text?.toString()?.trim().orEmpty()
                if (lightningAddressManager.isValidLightningAddress(address)) fetchMinThreshold(address)
            }
        })
        binding.percentageSlider.setLabelFormatter { getString(R.string.auto_withdraw_percentage_value, it.toInt()) }
    }

    private fun showAddressHelper(text: CharSequence?) {
        binding.lightningAddressLayout.error = null
        binding.lightningAddressLayout.helperText = text
    }

    private fun showThresholdEditDialog() {
        val minAmount = Amount(fetchedMinThresholdSats, Amount.Currency.BTC)
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.withdraw_auto_threshold_title),
                hint = AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS.toString(),
                initialValue = currentThreshold.toString(),
                suffix = Amount.Currency.BTC.symbol,
                helperText = getString(
                    R.string.withdraw_auto_threshold_range,
                    minAmount.toString(),
                    Amount(AutoWithdrawSettingsManager.MAX_THRESHOLD_SATS, Amount.Currency.BTC).toString()
                ),
                inputType = InputType.TYPE_CLASS_NUMBER,
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
                    amount != null && amount >= fetchedMinThresholdSats &&
                        amount <= AutoWithdrawSettingsManager.MAX_THRESHOLD_SATS
                }
            )
        )
    }

    private fun updateThresholdDisplay() {
        binding.thresholdRow.setTrailingText(Amount(currentThreshold, Amount.Currency.BTC).toString())
    }

    private fun loadSettings() {
        isUpdatingUI = true

        val enabled = settingsManager.isGloballyEnabled()
        binding.enableSwitch.isChecked = enabled
        binding.autoWithdrawConfigContainer.visibility = if (enabled) View.VISIBLE else View.GONE

        val savedAddress = settingsManager.getDefaultLightningAddress()
        binding.lightningAddressInput.setText(savedAddress)
        if (lightningAddressManager.isValidLightningAddress(savedAddress)) {
            fetchMinThreshold(savedAddress)
        }

        currentThreshold = settingsManager.getDefaultThreshold()
        updateThresholdDisplay()

        val percentage = settingsManager.getDefaultPercentage()
        binding.percentageSlider.value = percentage.toFloat()
        binding.percentageBadge.text = getString(R.string.auto_withdraw_percentage_value, percentage)

        isUpdatingUI = false
    }

    private fun fetchMinThreshold(address: String) {
        thresholdFetchJob?.cancel()
        showAddressHelper(getString(R.string.auto_withdraw_lightning_address_checking))
        val percentage = settingsManager.getDefaultPercentage()

        thresholdFetchJob = lifecycleScope.launch {
            val details = try {
                withContext(Dispatchers.IO) { lnUrlClient.fetchLnUrlDetails(address) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to check Lightning address minimum", e)
                null
            }
            // A blocking lookup can also throw after this job has been cancelled.
            ensureActive()

            if (details != null) {
                showAddressHelper(getString(R.string.auto_withdraw_lightning_address_valid))
                val minSendableSats = details.minSendable / 1000
                // Min threshold = minSendableSats * 100 / percentage, strictly greater than min.
                fetchedMinThresholdSats = (minSendableSats * 100 / percentage) + 1
                if (currentThreshold < fetchedMinThresholdSats) {
                    currentThreshold = fetchedMinThresholdSats
                    settingsManager.setDefaultThreshold(currentThreshold)
                    updateThresholdDisplay()
                }
            } else {
                binding.lightningAddressLayout.error = getString(R.string.auto_withdraw_lightning_address_invalid)
                fetchedMinThresholdSats = AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS
            }
        }
    }

    companion object {
        private const val TAG = "AutoWithdrawSettings"
    }
}
