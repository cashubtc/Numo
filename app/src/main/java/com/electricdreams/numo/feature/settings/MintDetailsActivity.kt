package com.electricdreams.numo.feature.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.MintIconCache
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.MintInfo

/**
 * Premium mint details screen inspired by cashu-me MintDetailsPage.
 * 
 * Features:
 * - Instant loading from cached mint info
 * - Async refresh with error handling
 * - Set as Lightning Mint action
 * - Copy URL, Delete actions
 * - Beautiful animations
 */
class MintDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MINT_URL = "mint_url"
        const val EXTRA_IS_LIGHTNING_MINT = "is_lightning_mint"
        const val EXTRA_DELETED = "deleted"
        const val EXTRA_SET_AS_LIGHTNING = "set_as_lightning"
        private const val TAG = "MintDetails"
    }

    // Header views
    private lateinit var backButton: ImageButton
    private lateinit var errorBanner: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorRetryButton: ImageButton
    private lateinit var iconContainer: FrameLayout
    private lateinit var mintIcon: com.google.android.material.imageview.ShapeableImageView
    private lateinit var mintName: TextView
    private lateinit var mintUrlText: TextView
    private lateinit var balanceText: TextView
    private lateinit var lightningBadge: LinearLayout

    // Content sections
    private lateinit var descriptionSection: LinearLayout
    private lateinit var descriptionText: TextView
    private lateinit var motdSection: LinearLayout
    private lateinit var motdText: TextView
    
    // Details section
    private lateinit var detailsSection: LinearLayout
    private lateinit var urlRow: View
    private lateinit var urlValue: TextView
    private lateinit var urlDivider: View
    private lateinit var softwareRow: View
    private lateinit var softwareValue: TextView
    private lateinit var softwareDivider: View
    private lateinit var versionRow: View
    private lateinit var versionValue: TextView
    private lateinit var contactSection: LinearLayout
    private lateinit var contactContainer: LinearLayout
    
    // Actions
    private lateinit var setLightningButton: LinearLayout
    private lateinit var actionDivider1: View
    private lateinit var copyUrlButton: LinearLayout
    private lateinit var deleteButton: LinearLayout

    // State
    private lateinit var mintManager: MintManager
    private var mintUrl: String = ""
    private var isLightningMint: Boolean = false
    private var hasFetchError: Boolean = false
    
    // Balance refresh broadcast receiver
    private val balanceRefreshReceiver: BroadcastReceiver = BalanceRefreshBroadcast.createReceiver { reason ->
        // Refresh balance when we receive a broadcast (e.g., from withdrawal success)
        loadBalance()
    }

    private val contactMethods = listOf(
        "nostr" to R.drawable.ic_contact_nostr,
        "telegram" to R.drawable.ic_contact_telegram,
        "twitter" to R.drawable.ic_contact_twitter,
        "email" to R.drawable.ic_contact_email
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mint_details)

        // Consistent edge-to-edge so nav pill floats above this detail sheet-style screen
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        mintUrl = intent.getStringExtra(EXTRA_MINT_URL) ?: run {
            finish()
            return
        }
        isLightningMint = intent.getBooleanExtra(EXTRA_IS_LIGHTNING_MINT, false)

        mintManager = MintManager.getInstance(this)
        MintIconCache.initialize(this)

        initViews()
        setupListeners()
        loadMintDetails()
        startEntranceAnimations()
    }
    
    override fun onStart() {
        super.onStart()
        // Register for balance refresh broadcasts
        BalanceRefreshBroadcast.register(this, balanceRefreshReceiver)
    }
    
    override fun onStop() {
        super.onStop()
        // Unregister balance refresh receiver
        BalanceRefreshBroadcast.unregister(this, balanceRefreshReceiver)
    }
    
    override fun onResume() {
        super.onResume()
        // Reload balance when returning to this screen
        loadBalance()
    }

    private fun initViews() {
        backButton = findViewById(R.id.back_button)
        errorBanner = findViewById(R.id.error_banner)
        errorText = findViewById(R.id.error_text)
        errorRetryButton = findViewById(R.id.error_retry_button)
        iconContainer = findViewById(R.id.icon_container)
        mintIcon = findViewById(R.id.mint_icon)
        mintName = findViewById(R.id.mint_name)
        mintUrlText = findViewById(R.id.mint_url)
        balanceText = findViewById(R.id.balance_text)
        lightningBadge = findViewById(R.id.lightning_badge)
        
        descriptionSection = findViewById(R.id.description_section)
        descriptionText = findViewById(R.id.description_text)
        motdSection = findViewById(R.id.motd_section)
        motdText = findViewById(R.id.motd_text)
        
        detailsSection = findViewById(R.id.details_section)
        urlRow = findViewById(R.id.url_row)
        urlValue = findViewById(R.id.url_value)
        urlDivider = findViewById(R.id.url_divider)
        softwareRow = findViewById(R.id.software_row)
        softwareValue = findViewById(R.id.software_value)
        softwareDivider = findViewById(R.id.software_divider)
        versionRow = findViewById(R.id.version_row)
        versionValue = findViewById(R.id.version_value)
        contactSection = findViewById(R.id.contact_section)
        contactContainer = findViewById(R.id.contact_container)
        
        setLightningButton = findViewById(R.id.set_lightning_button)
        actionDivider1 = findViewById(R.id.action_divider_1)
        copyUrlButton = findViewById(R.id.copy_url_button)
        deleteButton = findViewById(R.id.delete_button)
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }
        
        errorRetryButton.setOnClickListener {
            animateButtonTap(it) {
                refreshMintInfo()
            }
        }
        
        urlRow.setOnClickListener {
            copyToClipboard(mintUrl)
        }
        
        setLightningButton.setOnClickListener {
            animateButtonTap(it) {
                setAsLightningMint()
            }
        }
        
        copyUrlButton.setOnClickListener {
            animateButtonTap(it) {
                copyToClipboard(mintUrl)
            }
        }
        
        deleteButton.setOnClickListener {
            animateButtonTap(it) {
                showDeleteConfirmation()
            }
        }
    }

    private fun loadMintDetails() {
        // Basic info (always available)
        val displayName = mintManager.getMintDisplayName(mintUrl)
        val shortUrl = mintUrl.removePrefix("https://").removePrefix("http://")
        
        mintName.text = displayName
        mintUrlText.text = shortUrl
        urlValue.text = shortUrl
        
        // Update lightning badge visibility
        updateLightningBadge()
        
        // Load icon
        loadMintIcon()
        
        // Load balance
        loadBalance()
        
        // Load cached mint info first (instant)
        loadCachedMintInfo()
        
        // Then refresh from network (async)
        refreshMintInfo()
    }

    private fun updateLightningBadge() {
        if (isLightningMint) {
            lightningBadge.visibility = View.VISIBLE
            setLightningButton.visibility = View.GONE
            actionDivider1.visibility = View.GONE
        } else {
            lightningBadge.visibility = View.GONE
            setLightningButton.visibility = View.VISIBLE
            actionDivider1.visibility = View.VISIBLE
        }
    }

    private fun loadMintIcon() {
        val cachedFile = MintIconCache.getCachedIconFile(mintUrl)
        if (cachedFile != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    mintIcon.setImageBitmap(bitmap)
                    mintIcon.clearColorFilter()
                    return
                }
            } catch (e: Exception) {
                // Fall through to default
            }
        }
        
        mintIcon.setImageResource(R.drawable.ic_bitcoin)
        mintIcon.setColorFilter(getColor(R.color.color_primary))
    }

    private fun loadBalance() {
        lifecycleScope.launch {
            val balances = withContext(Dispatchers.IO) {
                CashuWalletManager.getAllMintBalances()
            }
            val balance = balances[mintUrl] ?: 0L
            balanceText.text = Amount(balance, Amount.Currency.BTC).toString()
        }
    }

    private fun loadCachedMintInfo() {
        // Try to load from cached JSON
        val cachedJson = mintManager.getMintInfo(mintUrl)
        if (cachedJson != null) {
            try {
                val info = CashuWalletManager.mintInfoFromJson(cachedJson)
                if (info != null) {
                    displayCachedMintInfo(info)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached mint info: ${'$'}{e.message}")
            }
        }
    }

    private fun displayCachedMintInfo(info: CashuWalletManager.CachedMintInfo) {
        val descriptionValue = info.descriptionLong ?: info.description
        if (!descriptionValue.isNullOrBlank()) {
            descriptionSection.visibility = View.VISIBLE
            descriptionText.text = descriptionValue
        }

        if (!info.motd.isNullOrBlank()) {
            motdSection.visibility = View.VISIBLE
            motdText.text = info.motd
        }

        // Version from cached structured data - show as separate rows
        val versionInfo = info.versionInfo
        val hasSoftware = !versionInfo?.name.isNullOrBlank()
        val hasVersion = !versionInfo?.version.isNullOrBlank()
        
        if (hasSoftware) {
            softwareRow.visibility = View.VISIBLE
            softwareDivider.visibility = View.VISIBLE
            softwareValue.text = versionInfo?.name
        } else {
            softwareRow.visibility = View.GONE
            softwareDivider.visibility = View.GONE
        }
        
        if (hasVersion) {
            versionRow.visibility = View.VISIBLE
            versionValue.text = versionInfo?.version
            // Only show URL divider if no software row above
            urlDivider.visibility = if (hasSoftware) View.VISIBLE else View.VISIBLE
        } else {
            versionRow.visibility = View.GONE
        }
        
        // Contact info from cached data
        displayContactInfo(info.contact)
    }
    
    private fun displayContactInfo(contacts: List<CashuWalletManager.CachedContactInfo>) {
        if (contacts.isEmpty()) {
            contactSection.visibility = View.GONE
            return
        }
        
        contactSection.visibility = View.VISIBLE
        contactContainer.removeAllViews()
        
        contacts.forEachIndexed { index, contact ->
            val contactView = layoutInflater.inflate(R.layout.item_mint_contact, contactContainer, false)
            
            val iconView = contactView.findViewById<ImageView>(R.id.contact_icon)
            val methodView = contactView.findViewById<TextView>(R.id.contact_method)
            val infoView = contactView.findViewById<TextView>(R.id.contact_info)
            val copyButton = contactView.findViewById<ImageView>(R.id.copy_button)
            
            // Set icon based on method
            val iconRes = when (contact.method.lowercase()) {
                "nostr" -> R.drawable.ic_contact_nostr
                "email" -> R.drawable.ic_contact_email
                "twitter", "x" -> R.drawable.ic_contact_twitter
                "telegram" -> R.drawable.ic_contact_telegram
                else -> R.drawable.ic_link
            }
            iconView.setImageResource(iconRes)
            
            // Set method label
            methodView.text = contact.method.uppercase()
            
            // Set info text
            infoView.text = contact.info
            
            // Copy button
            copyButton.setOnClickListener {
                copyToClipboard(contact.info)
            }
            
            contactContainer.addView(contactView)
            
            // Add divider if not last item
            if (index < contacts.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.divider_height)
                    ).apply {
                        marginStart = resources.getDimensionPixelSize(R.dimen.contact_divider_margin)
                    }
                    setBackgroundColor(getColor(R.color.color_divider))
                }
                contactContainer.addView(divider)
            }
        }
    }

    private fun refreshMintInfo() {
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    CashuWalletManager.fetchMintInfo(mintUrl)
                }
                
                if (info != null) {
                    // Cache the fresh info
                    withContext(Dispatchers.IO) {
                        val json = CashuWalletManager.mintInfoToJson(info)
                        mintManager.setMintInfo(mintUrl, json)
                        mintManager.setMintRefreshTimestamp(mintUrl)
                        
                        // Also refresh icon if available
                        info.iconUrl?.let { iconUrl ->
                            if (iconUrl.isNotEmpty()) {
                                MintIconCache.downloadAndCacheIcon(mintUrl, iconUrl)
                            }
                        }
                    }
                    
                    // Update UI
                    displayMintInfo(info)
                    hideError()
                    
                    // Reload icon in case it was refreshed
                    loadMintIcon()
                } else {
                    showError()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch mint info: ${e.message}")
                showError()
            }
        }
    }


    private fun displayMintInfo(info: MintInfo) {
        // Description
        val descriptionValue = info.descriptionLong ?: info.description
        if (!descriptionValue.isNullOrBlank()) {
            if (!descriptionSection.isVisible || descriptionText.text.toString() != descriptionValue) {
                descriptionSection.visibility = View.VISIBLE
                descriptionText.text = descriptionValue
                animateContentChange(descriptionSection)
            }
        } else {
            descriptionSection.visibility = View.GONE
        }

        // MOTD (Message of the Day)
        if (!info.motd.isNullOrBlank()) {
            if (!motdSection.isVisible || motdText.text.toString() != info.motd) {
                motdSection.visibility = View.VISIBLE
                motdText.text = info.motd
                animateContentChange(motdSection, startDelay = 50)
            }
        } else {
            motdSection.visibility = View.GONE
        }
        
        // Version - show as separate rows
        val versionName = info.version?.name
        val versionNumberVal = info.version?.version
        val hasSoftware = !versionName.isNullOrBlank()
        val hasVersion = !versionNumberVal.isNullOrBlank()
        
        if (hasSoftware) {
            val newSoftwareValue = versionName!!
            if (!softwareRow.isVisible || softwareValue.text.toString() != newSoftwareValue) {
                softwareRow.visibility = View.VISIBLE
                softwareDivider.visibility = View.VISIBLE
                softwareValue.text = newSoftwareValue
            }
        } else {
            softwareRow.visibility = View.GONE
            softwareDivider.visibility = View.GONE
        }
        
        if (hasVersion) {
            val newVersionValue = versionNumberVal!!
            if (!versionRow.isVisible || versionValue.text.toString() != newVersionValue) {
                versionRow.visibility = View.VISIBLE
                versionValue.text = newVersionValue
            }
        } else {
            versionRow.visibility = View.GONE
        }
        
        // Contact info from live MintInfo
        val contacts = info.contact?.map { 
            CashuWalletManager.CachedContactInfo(it.method, it.info) 
        } ?: emptyList()
        displayContactInfo(contacts)
    }


    private fun animateContentChange(target: View, startDelay: Long = 0, duration: Long = 250) {
        target.alpha = 0f
        target.translationY = 10f
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    private fun showError() {
        hasFetchError = true
        errorBanner.visibility = View.VISIBLE
        errorBanner.alpha = 0f
        errorBanner.translationY = -20f
        errorBanner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }


    private fun hideError() {
        if (hasFetchError) {
            hasFetchError = false
            errorBanner.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(200)
                .withEndAction {
                    errorBanner.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setAsLightningMint() {
        isLightningMint = true
        updateLightningBadge()
        
        // Animate the badge appearance
        lightningBadge.alpha = 0f
        lightningBadge.scaleX = 0.8f
        lightningBadge.scaleY = 0.8f
        lightningBadge.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
        
        // Set result for parent activity
        val resultIntent = Intent().apply {
            putExtra(EXTRA_MINT_URL, mintUrl)
            putExtra(EXTRA_SET_AS_LIGHTNING, true)
        }
        setResult(RESULT_OK, resultIntent)
        
        Toast.makeText(this, R.string.mints_lightning_changed, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mint URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.mint_details_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmation() {
        val displayName = mintManager.getMintDisplayName(mintUrl)
        
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.mints_remove_title),
                message = getString(R.string.mints_remove_message, displayName),
                confirmText = getString(R.string.mints_remove_confirm),
                cancelText = getString(R.string.common_cancel),
                isDestructive = true,
                onConfirm = { deleteMint() }
            )
        )
    }

    private fun deleteMint() {
        mintManager.removeMint(mintUrl)
        
        // Broadcast that a mint was removed so other activities can refresh
        BalanceRefreshBroadcast.send(this, BalanceRefreshBroadcast.REASON_MINT_REMOVED)
        
        val resultIntent = Intent().apply {
            putExtra(EXTRA_MINT_URL, mintUrl)
            putExtra(EXTRA_DELETED, true)
        }
        setResult(RESULT_OK, resultIntent)
        
        Toast.makeText(this, getString(R.string.mints_removed_toast), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun animateButtonTap(view: View, onComplete: () -> Unit) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .withEndAction { onComplete() }
                    .start()
            }
            .start()
    }

    private fun startEntranceAnimations() {
        // Icon bounce
        iconContainer.scaleX = 0f
        iconContainer.scaleY = 0f
        iconContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
        
        // Name fade in
        mintName.alpha = 0f
        mintName.translationY = 20f
        mintName.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(100)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Balance pill slide up
        balanceText.alpha = 0f
        balanceText.translationY = 20f
        balanceText.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(150)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Details card slide up
        detailsSection.alpha = 0f
        detailsSection.translationY = 30f
        detailsSection.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(200)
            .setDuration(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
}
