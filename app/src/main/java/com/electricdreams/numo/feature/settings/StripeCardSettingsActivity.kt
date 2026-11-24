package com.electricdreams.numo.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityStripeCardSettingsBinding
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeCardConnectionTester
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.payment.card.StripeTerminalManager
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Configures card payments through the merchant's own Stripe account.
 *
 * Like the BTCPay settings, the form saves itself: when the page is left or card payments are
 * switched on. The restricted key is stored encrypted, only once it is a valid restricted key,
 * and is never shown again after saving.
 */
class StripeCardSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStripeCardSettingsBinding
    private lateinit var settings: StripeCardSettings
    private val connectionTester = StripeCardConnectionTester()

    private var isTestingConnection = false
    private var isUpdatingSwitch = false
    private var isLoadingSettings = false
    private var enableAfterConnectionTest = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            testConnection()
        } else {
            enableAfterConnectionTest = false
            setStatus(getString(R.string.card_payment_status_location_required), R.color.color_error)
            updateSwitch()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStripeCardSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        settings = StripeCardSettings.getInstance(this)
        setupListeners()
        loadSettings()
    }

    private fun setupListeners() {
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }

        binding.enableToggleRow.setOnClickListener {
            if (binding.stripeCardEnableSwitch.isEnabled) binding.stripeCardEnableSwitch.performClick()
        }
        binding.stripeCardEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            if (!isChecked) {
                enableAfterConnectionTest = false
                settings.isEnabled = false
                updateSwitch()
                return@setOnCheckedChangeListener
            }
            enableAfterConnectionTest = true
            if (!persistForm()) {
                enableAfterConnectionTest = false
                updateSwitch()
                return@setOnCheckedChangeListener
            }
            updateSwitch()
            testConnection()
        }
        binding.stripeRestrictedKeyInput.doAfterTextChanged { onFieldEdited() }
        binding.stripeLocationInput.doAfterTextChanged { onFieldEdited() }
        binding.testConnectionRow.setOnClickListener {
            enableAfterConnectionTest = false
            testConnection()
        }
        binding.removeKeyRow.setOnClickListener { confirmRemoveKey() }
        binding.stripeTutorialRow.setOnClickListener {
            startActivity(Intent(this, StripeCardTutorialActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        persistForm()
    }

    private fun loadSettings() {
        isLoadingSettings = true
        binding.stripeLocationInput.setText(settings.storedLocationId())
        binding.stripeRestrictedKeyInput.setText("")
        isLoadingSettings = false
        binding.stripeRestrictedKeyLayout.error = null
        binding.stripeLocationLayout.error = null
        val hasKey = settings.hasStoredKey()
        binding.stripeRestrictedKeyLayout.helperText = if (hasKey) {
            getString(R.string.stripe_card_restricted_key_saved_hint)
        } else {
            null
        }
        binding.removeKeyRow.visibility = if (hasKey) View.VISIBLE else View.GONE
        updateSwitch()
    }

    private fun onFieldEdited() {
        if (isLoadingSettings) return
        showFieldErrors(onlyNonEmpty = true)
        updateSwitch()
    }

    private fun updateSwitch() {
        val deviceSupported = StripeTerminalManager.isDeviceSupported(this)
        val canEnable = deviceSupported && formConfig() != null && !isTestingConnection
        isUpdatingSwitch = true
        binding.stripeCardEnableSwitch.isEnabled = canEnable
        binding.stripeCardEnableSwitch.isChecked =
            canEnable && settings.isEnabled && settings.loadConfig() != null
        isUpdatingSwitch = false
        binding.enableSubtitle.text = getString(
            if (deviceSupported) R.string.stripe_card_enable_subtitle
            else R.string.settings_stripe_card_unsupported
        )
    }

    /** The config the form describes, using the stored key when the key field is empty. */
    private fun formConfig(): StripeCardConfig? {
        val key = typedKey().ifEmpty { settings.loadConfig()?.restrictedKey.orEmpty() }
        val locationId = typedLocation()
        if (!StripeCardConfig.isValidRestrictedKey(key)) return null
        if (!StripeCardConfig.isValidLocationId(locationId)) return null
        return StripeCardConfig(key, locationId)
    }

    private fun typedKey() = binding.stripeRestrictedKeyInput.text?.toString()?.trim().orEmpty()

    private fun typedLocation() = binding.stripeLocationInput.text?.toString()?.trim().orEmpty()

    /** Shows field errors; while typing, an empty field is not yet an error. */
    private fun showFieldErrors(onlyNonEmpty: Boolean) {
        val key = typedKey()
        val keyMissing = key.isEmpty() && !settings.hasStoredKey()
        val keyInvalid = if (key.isEmpty()) keyMissing && !onlyNonEmpty
        else !StripeCardConfig.isValidRestrictedKey(key)
        binding.stripeRestrictedKeyLayout.error =
            if (keyInvalid) getString(R.string.stripe_card_invalid_key) else null

        val location = typedLocation()
        val locationInvalid = if (location.isEmpty()) !onlyNonEmpty
        else !StripeCardConfig.isValidLocationId(location)
        binding.stripeLocationLayout.error =
            if (locationInvalid) getString(R.string.stripe_card_invalid_location) else null
    }

    /**
     * Saves the form when it describes a complete, valid config that differs from what is stored.
     *
     * @return true when a valid config is stored afterwards.
     */
    private fun persistForm(): Boolean {
        val config = formConfig() ?: return false
        if (config == settings.loadConfig()) return true
        if (!settings.saveConfig(config)) {
            Toast.makeText(this, R.string.stripe_card_save_failed, Toast.LENGTH_LONG).show()
            return false
        }
        // A different key or location must not reuse the previous reader session.
        StripeTerminalManager.reset()
        if (!isFinishing) loadSettings()
        return true
    }

    private fun testConnection() {
        if (isTestingConnection) return
        val config = formConfig()
        if (config == null) {
            enableAfterConnectionTest = false
            showFieldErrors(onlyNonEmpty = false)
            updateSwitch()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateSwitch()
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }

        isTestingConnection = true
        updateSwitch()
        setStatus(getString(R.string.stripe_card_test_connecting), R.color.color_text_secondary)

        lifecycleScope.launch {
            val result = runCatching { connectionTester.validate(this@StripeCardSettingsActivity, config) }
            isTestingConnection = false
            result.onSuccess {
                if (enableAfterConnectionTest && config == formConfig()) settings.isEnabled = true
                enableAfterConnectionTest = false
                val message = if (config.isTestMode) {
                    R.string.stripe_card_test_success_test_mode
                } else {
                    R.string.stripe_card_test_success
                }
                setStatus(getString(message), R.color.color_success_green)
            }.onFailure { e ->
                enableAfterConnectionTest = false
                Log.w(TAG, "Stripe connection and device test failed", e)
                val reason = e.message ?: e.javaClass.simpleName
                setStatus(getString(R.string.stripe_card_test_failed, reason), R.color.color_error)
            }
            updateSwitch()
        }
    }

    private fun confirmRemoveKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stripe_card_remove_title)
            .setMessage(R.string.stripe_card_remove_subtitle)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.clear()
                StripeTerminalManager.reset()
                Toast.makeText(this, R.string.stripe_card_removed, Toast.LENGTH_SHORT).show()
                loadSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.testConnectionStatus.text = text
        binding.testConnectionStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    companion object {
        private const val TAG = "StripeCardSettings"
    }
}
