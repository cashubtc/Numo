package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.items.ItemListActivity
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinProtectionHelper
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.feature.tips.TipsSettingsActivity
import com.electricdreams.numo.feature.baskets.BasketNamesSettingsActivity
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsActivity

/**
 * Main Settings screen.
 * 
 * PIN-protected items:
 * - Mints Settings (can withdraw funds)
 * - Items Settings (can modify prices)
 * 
 * Developer section is hidden by default and only shown when
 * developer mode is enabled (by tapping version 5 times in About).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private var pendingDestination: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Settings root should also draw under the nav pill for consistency
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        pinManager = PinManager.getInstance(this)

        setupViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Update developer section visibility when returning from About
        updateDeveloperSectionVisibility()
    }

    private fun setupViews() {
        updateDeveloperSectionVisibility()
    }

    private fun updateDeveloperSectionVisibility() {
        val developerSection = findViewById<View>(R.id.developer_section)
        developerSection.visibility = if (DeveloperPrefs.isDeveloperModeEnabled(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setupListeners() {
        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        // === Terminal Section ===
        
        // Items - protected because it allows modifying prices
        findViewById<View>(R.id.items_settings_item).setOnClickListener {
            openProtectedActivity(ItemListActivity::class.java)
        }

        // Tips - unprotected (tips just add to balance)
        findViewById<View>(R.id.tips_settings_item).setOnClickListener {
            startActivity(Intent(this, TipsSettingsActivity::class.java))
        }

        // Basket Names - unprotected (just preset names)
        findViewById<View>(R.id.basket_names_settings_item).setOnClickListener {
            startActivity(Intent(this, BasketNamesSettingsActivity::class.java))
        }

        // === Payments Section ===

        findViewById<View>(R.id.currency_settings_item).setOnClickListener {
            startActivity(Intent(this, CurrencySettingsActivity::class.java))
        }

        // Mints - protected (can withdraw funds)
        findViewById<View>(R.id.mints_settings_item).setOnClickListener {
            openProtectedActivity(MintsSettingsActivity::class.java)
        }

        // Withdrawals - protected (handles funds)
        findViewById<View>(R.id.withdrawals_settings_item).setOnClickListener {
            openProtectedActivity(AutoWithdrawSettingsActivity::class.java)
        }

        // BTCPay Server - protected (holds API key)
        findViewById<View>(R.id.btcpay_settings_item).setOnClickListener {
            openProtectedActivity(BtcPaySettingsActivity::class.java)
        }

        // === Security Section ===

        // Security settings - always accessible (contains PIN setup itself)
        findViewById<View>(R.id.security_settings_item).setOnClickListener {
            startActivity(Intent(this, SecuritySettingsActivity::class.java))
        }

        // === Appearance Section ===

        findViewById<View>(R.id.theme_settings_item).setOnClickListener {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }

        // Language settings (look up by name to avoid compile-time dependency if R is stale)
        val languageItemId = resources.getIdentifier("language_settings_item", "id", packageName)
        findViewById<View?>(languageItemId)?.setOnClickListener {
            startActivity(Intent(this, LanguageSettingsActivity::class.java))
        }

        // === About Section ===

        findViewById<View>(R.id.about_item).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // === Developer Section (only visible if enabled) ===

        findViewById<View>(R.id.developer_settings_item).setOnClickListener {
            startActivity(Intent(this, DeveloperSettingsActivity::class.java))
        }
    }

    private fun openProtectedActivity(destination: Class<*>) {
        if (pinManager.isPinEnabled() && !PinProtectionHelper.isRecentlyVerified()) {
            // Need PIN verification
            pendingDestination = destination
            val intent = Intent(this, PinEntryActivity::class.java).apply {
                putExtra(PinEntryActivity.EXTRA_TITLE, getString(R.string.dialog_title_enter_pin))
                putExtra(PinEntryActivity.EXTRA_SUBTITLE, getString(R.string.settings_verify_pin_subtitle))
            }
            startActivityForResult(intent, REQUEST_PIN_VERIFY)
        } else {
            // No PIN or recently verified
            startActivity(Intent(this, destination))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_PIN_VERIFY && resultCode == Activity.RESULT_OK) {
            PinProtectionHelper.markVerified()
            pendingDestination?.let { destination ->
                startActivity(Intent(this, destination))
            }
        }
        pendingDestination = null
    }

    companion object {
        private const val REQUEST_PIN_VERIFY = 1001
    }
}
