package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.BuildConfig
import com.electricdreams.numo.R

/**
 * About screen showing app information, device info, and links.
 * 
 * Easter egg: Tap the version number 5 times to enable Developer Settings.
 */
class AboutActivity : AppCompatActivity() {

    private var versionTapCount = 0
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        setupViews()
        populateDeviceInfo()
        setupListeners()
    }

    private fun setupViews() {
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        // Set version text
        val versionText = findViewById<TextView>(R.id.version_text)
        versionText.text = "Version ${BuildConfig.VERSION_NAME}"
    }

    private fun populateDeviceInfo() {
        // App Version
        findViewById<TextView>(R.id.info_app_version).text = BuildConfig.VERSION_NAME

        // Build Number
        findViewById<TextView>(R.id.info_build_number).text = BuildConfig.VERSION_CODE.toString()

        // Device Model
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
        findViewById<TextView>(R.id.info_device).text = deviceName

        // Android Version
        findViewById<TextView>(R.id.info_android_version).text = 
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    private fun setupListeners() {
        // Version tap for developer mode
        findViewById<TextView>(R.id.version_text).setOnClickListener {
            handleVersionTap()
        }

        // Terms of Service
        findViewById<View>(R.id.terms_item).setOnClickListener {
            showTermsDialog()
        }

        // Privacy Policy
        findViewById<View>(R.id.privacy_item).setOnClickListener {
            showPrivacyDialog()
        }

        // Website
        findViewById<View>(R.id.website_item).setOnClickListener {
            openUrl("https://numopay.org")
        }

        // Contact
        findViewById<View>(R.id.contact_item).setOnClickListener {
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
                    Toast.makeText(this, "Developer mode is already enabled", Toast.LENGTH_SHORT).show()
                }
            }
            versionTapCount >= 5 -> {
                // Enable developer mode
                DeveloperPrefs.setDeveloperModeEnabled(this, true)
                Toast.makeText(this, "🎉 Developer mode enabled!", Toast.LENGTH_LONG).show()
                versionTapCount = 0
            }
            versionTapCount >= 3 -> {
                val remaining = 5 - versionTapCount
                Toast.makeText(this, "$remaining more taps to enable developer mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTermsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Terms of Service")
            .setMessage(TERMS_OF_SERVICE)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Privacy Policy")
            .setMessage(PRIVACY_POLICY)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendEmail(email: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$email")
                putExtra(Intent.EXTRA_SUBJECT, "Numo Support")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val TERMS_OF_SERVICE = """
            NUMO WALLET - TERMS OF SERVICE
            
            1. ACCEPTANCE
            By using this wallet application, you agree to these terms.
            
            2. NATURE OF SERVICE
            This is a self-custodial Bitcoin wallet using Cashu ecash technology. You are solely responsible for your funds and seed phrase.
            
            3. SEED PHRASE
            Your 12-word seed phrase is the ONLY way to recover your wallet. Never share it. Store it securely offline. We cannot recover lost seed phrases.
            
            4. NO WARRANTY
            This software is provided "as is" without warranty of any kind. Use at your own risk.
            
            5. LIMITATION OF LIABILITY
            We are not liable for any loss of funds, whether through bugs, user error, or third-party mint failures.
            
            6. ECASH MINTS
            Ecash tokens are held by third-party mints. These mints may fail or become unavailable. Diversify across multiple mints.
            
            7. PRIVACY
            This wallet does not collect personal data. Transactions are processed through ecash mints which may have their own privacy policies.
            
            8. UPDATES
            These terms may be updated. Continued use constitutes acceptance.
            
            Last updated: November 2024
        """.trimIndent()

        private val PRIVACY_POLICY = """
            NUMO WALLET - PRIVACY POLICY
            
            1. DATA COLLECTION
            Numo does not collect, store, or transmit any personal data. The app runs entirely on your device.
            
            2. WALLET DATA
            Your seed phrase and wallet data are stored locally on your device. We have no access to this information.
            
            3. NETWORK COMMUNICATIONS
            The app communicates with:
            • Cashu mints (to manage ecash tokens)
            • Price APIs (to fetch exchange rates)
            • Nostr relays (for optional backup features)
            
            These services may have their own privacy policies.
            
            4. ANALYTICS
            We do not use any analytics or tracking services.
            
            5. THIRD-PARTY MINTS
            When using ecash mints, the mint operator may see transaction amounts and timing. Choose trusted mints.
            
            6. BACKUP
            If you enable Nostr backup, encrypted wallet data is published to Nostr relays. Only you can decrypt this data with your seed phrase.
            
            7. CONTACT
            For privacy questions: numopay@proton.me
            
            Last updated: November 2024
        """.trimIndent()
    }
}
