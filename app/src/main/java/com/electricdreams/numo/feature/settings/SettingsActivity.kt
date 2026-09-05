package com.electricdreams.numo.feature.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged

import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.ActivitySettingsBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsActivity
import com.electricdreams.numo.feature.baskets.BasketNamesSettingsActivity
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.feature.items.ItemListActivity
import com.electricdreams.numo.feature.pin.PinEntryActivity
import com.electricdreams.numo.feature.pin.PinManager
import com.electricdreams.numo.feature.pin.PinProtectionHelper
import com.electricdreams.numo.feature.tips.TipsSettingsActivity
import com.electricdreams.numo.payment.DefaultPaymentMethodManager
import com.electricdreams.numo.payment.PaymentTabManager.PaymentTab
import com.electricdreams.numo.ui.components.SettingsRowView
import com.electricdreams.numo.util.startActivityForResultCompat

/** Searchable settings directory. Sensitive destinations retain their PIN gate. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var pinManager: PinManager
    private lateinit var sections: List<SettingsSection>
    private var pendingDestination: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdgeWithPill(this)
        setupInsets()

        pinManager = PinManager.getInstance(this)
        sections = listOf(
            SettingsSection(binding.checkoutSection, R.string.settings_section_checkout),
            SettingsSection(binding.paymentsSection, R.string.settings_section_payments),
            SettingsSection(binding.connectionsSection, R.string.settings_section_connections),
            SettingsSection(binding.securitySection, R.string.settings_section_security),
            SettingsSection(binding.generalSection, R.string.settings_section_general),
            SettingsSection(binding.developerSection, R.string.settings_section_developer),
        )
        sections.forEach { section ->
            ViewCompat.setAccessibilityHeading(section.container.getChildAt(0), true)
        }
        setupListeners()
        binding.settingsSearch.doAfterTextChanged { filterSettings() }
    }

    override fun onResume() {
        super.onResume()
        updateSummaries()
        filterSettings()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The shared helper already applies the status bar's top inset.
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.settingsScroll.setPadding(0, 0, 0, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
    }

    private fun updateSummaries() {
        val prefs = PreferenceStore.app(this)
        val btcPayEnabled = prefs.getBoolean("btcpay_enabled", false)
        binding.btcpaySettingsItem.setSubtitle(getString(
            if (btcPayEnabled) R.string.settings_btcpay_enabled else R.string.settings_btcpay_disabled
        ))
        updateAvailability(binding.mintsSettingsItem, !btcPayEnabled,
            R.string.settings_item_mints_subtitle, R.string.settings_unavailable_btcpay)
        updateAvailability(binding.withdrawalsSettingsItem, !btcPayEnabled,
            R.string.settings_item_withdrawals_subtitle, R.string.settings_unavailable_btcpay)
        updateAvailability(binding.webhooksSettingsItem, !btcPayEnabled,
            R.string.settings_item_webhooks_subtitle, R.string.settings_unavailable_btcpay)

        val supportsFiat = MintManager.getInstance(this).getPreferredUnit().equals("sat", true)
        binding.currencySettingsItem.isEnabled = supportsFiat
        binding.currencySettingsItem.showChevron(supportsFiat)
        binding.currencySettingsItem.setSubtitle(if (supportsFiat) {
            CurrencyManager.getInstance(this).getCurrentCurrency()
        } else {
            getString(R.string.settings_fiat_conversion_disabled_custom_unit)
        })

        val method = DefaultPaymentMethodManager.getInstance(this).getDefaultPaymentMethod()
        binding.defaultPaymentMethodSettingsItem.setSubtitle(getString(when (method) {
            PaymentTab.UNIFIED -> R.string.default_payment_method_option_unified
            PaymentTab.CASHU -> R.string.default_payment_method_option_cashu
            PaymentTab.LIGHTNING -> R.string.default_payment_method_option_lightning
        }))

        val theme = when (prefs.getString(ThemeSettingsActivity.PREF_THEME)) {
            ThemeSettingsActivity.THEME_OBSIDIAN -> R.string.theme_settings_option_obsidian
            ThemeSettingsActivity.THEME_BITCOIN_ORANGE -> R.string.theme_settings_option_bitcoin_orange
            ThemeSettingsActivity.THEME_WHITE -> R.string.theme_settings_option_white
            else -> R.string.theme_settings_option_green
        }
        val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        binding.themeSettingsItem.setSubtitle(getString(
            R.string.settings_theme_summary,
            getString(theme),
            getString(if (darkMode) R.string.settings_mode_dark else R.string.settings_mode_light),
        ))
        val locale = AppCompatDelegate.getApplicationLocales()[0] ?: resources.configuration.locales[0]
        binding.languageSettingsItem.setSubtitle(getString(when (locale.language) {
            "es" -> R.string.language_settings_option_spanish
            "pt" -> R.string.language_settings_option_portuguese
            "ko" -> R.string.language_settings_option_korean
            "ja" -> R.string.language_settings_option_japanese
            "de" -> R.string.language_settings_option_german
            else -> R.string.language_settings_option_english
        }))
    }

    private fun updateAvailability(
        row: SettingsRowView,
        enabled: Boolean,
        @StringRes description: Int,
        @StringRes unavailableReason: Int,
    ) {
        row.isEnabled = enabled
        row.showChevron(enabled)
        row.setSubtitle(getString(if (enabled) description else unavailableReason))
    }

    private fun filterSettings() {
        val words = binding.settingsSearch.text.toString().trim().split(Regex("\\s+"))
        val developerEnabled = DeveloperPrefs.isDeveloperModeEnabled(this)
        sections.forEach { section ->
            val available = section.container !== binding.developerSection || developerEnabled
            section.rows.forEach { (row, description) ->
                val text = "${getString(section.title)} $description ${row.searchableText}"
                row.isVisible = available && words.all { text.contains(it, ignoreCase = true) }
            }
            section.container.isVisible = available && section.rows.any { it.first.isVisible }
        }
        binding.searchEmptyState.isVisible = sections.none { it.container.isVisible }
    }

    private fun setupListeners() = with(binding) {
        topBar.setNavigationOnClickListener { finish() }
        itemsSettingsItem.setOnClickListener { openProtectedActivity(ItemListActivity::class.java) }
        tipsSettingsItem.setOnClickListener { openActivity(TipsSettingsActivity::class.java) }
        basketNamesSettingsItem.setOnClickListener { openActivity(BasketNamesSettingsActivity::class.java) }
        currencySettingsItem.setOnClickListener {
            if (currencySettingsItem.isEnabled) openActivity(CurrencySettingsActivity::class.java)
        }
        defaultPaymentMethodSettingsItem.setOnClickListener {
            openActivity(DefaultPaymentMethodSettingsActivity::class.java)
        }
        mintsSettingsItem.setOnClickListener {
            if (mintsSettingsItem.isEnabled) openProtectedActivity(MintsSettingsActivity::class.java)
        }
        withdrawalsSettingsItem.setOnClickListener {
            if (withdrawalsSettingsItem.isEnabled) {
                openProtectedActivity(AutoWithdrawSettingsActivity::class.java)
            }
        }
        webhooksSettingsItem.setOnClickListener {
            if (webhooksSettingsItem.isEnabled) openProtectedActivity(WebhookSettingsActivity::class.java)
        }
        btcpaySettingsItem.setOnClickListener { openProtectedActivity(BtcPaySettingsActivity::class.java) }
        securitySettingsItem.setOnClickListener { openActivity(SecuritySettingsActivity::class.java) }
        languageSettingsItem.setOnClickListener { openActivity(LanguageSettingsActivity::class.java) }
        themeSettingsItem.setOnClickListener { openActivity(ThemeSettingsActivity::class.java) }
        aboutItem.setOnClickListener { openActivity(AboutActivity::class.java) }
        developerSettingsItem.setOnClickListener { openActivity(DeveloperSettingsActivity::class.java) }
    }

    private fun openActivity(destination: Class<*>) {
        startActivity(Intent(this, destination))
    }

    private fun openProtectedActivity(destination: Class<*>) {
        if (pinManager.isPinEnabled() && !PinProtectionHelper.isRecentlyVerified()) {
            pendingDestination = destination
            val intent = Intent(this, PinEntryActivity::class.java).apply {
                putExtra(PinEntryActivity.EXTRA_TITLE, getString(R.string.dialog_title_enter_pin))
                putExtra(PinEntryActivity.EXTRA_SUBTITLE, getString(R.string.settings_verify_pin_subtitle))
            }
            startActivityForResultCompat(intent, REQUEST_PIN_VERIFY)
        } else {
            openActivity(destination)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PIN_VERIFY && resultCode == Activity.RESULT_OK) {
            PinProtectionHelper.markVerified()
            pendingDestination?.let(::openActivity)
        }
        pendingDestination = null
    }

    private class SettingsSection(val container: LinearLayout, @param:StringRes val title: Int) {
        // Keep the original descriptions searchable after live summaries replace them.
        val rows = (container.getChildAt(1) as LinearLayout).children
            .filterIsInstance<SettingsRowView>()
            .map { it to it.searchableText }
            .toList()
    }

    companion object {
        private const val REQUEST_PIN_VERIFY = 1001
    }
}
