package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityAboutBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * About screen showing app information, device info, and links.
 *
 * Easter egg: Tap the version number 5 times to enable Developer Settings.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    private var versionTapCount = 0
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        setupViews()
        populateDeviceInfo()
        setupListeners()
    }

    private fun setupViews() {
        binding.topBar.onNavClick { finish() }

        // Set version text
        val versionText = binding.versionText
        versionText.text = "Version ${BuildConfig.VERSION_NAME}"
    }

    private fun populateDeviceInfo() {
        // App Version
        binding.infoAppVersion.text = BuildConfig.VERSION_NAME

        // Build Number
        binding.infoBuildNumber.text = BuildConfig.VERSION_CODE.toString()

        // Device Model
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
        binding.infoDevice.text = deviceName

        // Android Version
        binding.infoAndroidVersion.text =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    private fun setupListeners() {
        // Version tap for developer mode
        binding.versionText.setOnClickListener {
            handleVersionTap()
        }

        // Terms of Service
        binding.termsItem.setOnClickListener {
            showTermsDialog()
        }

        // Privacy Policy
        binding.privacyItem.setOnClickListener {
            showPrivacyDialog()
        }

        // Website
        binding.websiteItem.setOnClickListener {
            openUrl("https://numopay.org")
        }

        // Contact
        binding.contactItem.setOnClickListener {
            sendEmail("numopay@proton.me")
        }
    }

    private fun handleVersionTap() {
        val now = System.currentTimeMillis()

        // Reset counter if more than 2 seconds since last tap
        if (now - lastTapTime > 2000) {
            versionTapCount = 0
        }
        lastTapTime = now
        versionTapCount++

        val isDeveloperModeEnabled = DeveloperPrefs.isDeveloperModeEnabled(this)

        when {
            isDeveloperModeEnabled -> {
                // Already enabled
                if (versionTapCount == 1) {
                    Toast.makeText(this, R.string.settings_about_toast_developer_already_enabled, Toast.LENGTH_SHORT).show()
                }
            }
            versionTapCount >= 5 -> {
                // Enable developer mode
                DeveloperPrefs.setDeveloperModeEnabled(this, true)
                Toast.makeText(this, R.string.settings_about_toast_developer_enabled, Toast.LENGTH_LONG).show()
                versionTapCount = 0
            }
            versionTapCount >= 3 -> {
                val remaining = 5 - versionTapCount
                Toast.makeText(this, getString(R.string.settings_about_toast_developer_taps_remaining, remaining), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTermsDialog() {
        val textView = android.widget.TextView(this).apply {
            text = getString(R.string.terms_of_service_full)
            typeface = android.graphics.Typeface.MONOSPACE
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            textSize = 12f
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_about_dialog_terms_title)
            .setView(scrollView)
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    private fun showPrivacyDialog() {
        val textView = android.widget.TextView(this).apply {
            text = getString(R.string.privacy_policy_full)
            typeface = android.graphics.Typeface.MONOSPACE
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            textSize = 12f
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_about_dialog_privacy_title)
            .setView(scrollView)
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.settings_about_toast_link_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendEmail(email: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$email")
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_email_subject))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.settings_about_toast_no_email, Toast.LENGTH_SHORT).show()
        }
    }
}
