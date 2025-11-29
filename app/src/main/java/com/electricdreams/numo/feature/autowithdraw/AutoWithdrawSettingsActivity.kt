package com.electricdreams.numo.feature.autowithdraw

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.settings.WithdrawLightningActivity
import com.electricdreams.numo.ui.components.MintSelectionBottomSheet
import com.electricdreams.numo.ui.util.DialogHelper
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Premium Apple-like settings screen for automatic withdrawals.
 * 
 * Features a beautiful hero section, card-based settings groups,
 * smooth animations, and a clean transaction history with expandable
 * error details for failed withdrawals.
 */
class AutoWithdrawSettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: AutoWithdrawSettingsManager
    private lateinit var autoWithdrawManager: AutoWithdrawManager

    // Hero section
    private lateinit var heroIcon: ImageView
    private lateinit var heroIconContainer: FrameLayout
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    // Settings controls
    private lateinit var enableSwitch: SwitchCompat
    private lateinit var enableToggleRow: LinearLayout
    private lateinit var lightningAddressInput: EditText
    private lateinit var thresholdDisplay: TextView
    private lateinit var percentageSlider: Slider
    private lateinit var percentageBadge: TextView

    // History section
    private lateinit var historyCard: CardView
    private lateinit var historyEmptyContainer: LinearLayout
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var seeAllButton: TextView

    // Manual withdraw
    private lateinit var manualWithdrawRow: LinearLayout
    
    // Manager for mint info
    private lateinit var mintManager: MintManager

    private var isUpdatingUI = false
    private var iconAnimator: ObjectAnimator? = null
    
    // Current threshold value (in sats)
    private var currentThreshold: Long = AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_withdraw_settings)

        // Global helper: draw content under system bars so nav pill floats over cards
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        settingsManager = AutoWithdrawSettingsManager.getInstance(this)
        autoWithdrawManager = AutoWithdrawManager.getInstance(this)
        mintManager = MintManager.getInstance(this)

        initViews()
        setupListeners()
        loadSettings()
        loadHistory()
        
        // Start entrance animations
        startEntranceAnimations()
    }

    private fun initViews() {
        // Back button - use standard chevron pattern for consistency
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Hero section
        heroIcon = findViewById(R.id.hero_icon)
        heroIconContainer = findViewById(R.id.icon_container)
        statusContainer = findViewById(R.id.status_container)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)

        // Main toggle
        enableSwitch = findViewById(R.id.enable_switch)
        enableToggleRow = findViewById(R.id.enable_toggle_row)

        // Config inputs
        lightningAddressInput = findViewById(R.id.lightning_address_input)
        thresholdDisplay = findViewById(R.id.threshold_display)
        percentageSlider = findViewById(R.id.percentage_slider)
        percentageBadge = findViewById(R.id.percentage_badge)

        // History
        historyCard = findViewById(R.id.history_card)
        historyEmptyContainer = findViewById(R.id.history_empty_container)
        historyRecyclerView = findViewById(R.id.history_recycler_view)
        seeAllButton = findViewById(R.id.see_all_button)
        
        // Manual withdraw
        manualWithdrawRow = findViewById(R.id.manual_withdraw_row)

        historyRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupListeners() {
        // Toggle row click (toggles switch)
        enableToggleRow.setOnClickListener {
            enableSwitch.toggle()
        }

        // Enable switch
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUI) {
                settingsManager.setGloballyEnabled(isChecked)
                updateStatusIndicator(isChecked)
                updateConfigFieldsEnabled(isChecked)
                animateStatusChange(isChecked)
            }
        }

        // Lightning address
        lightningAddressInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUI) {
                    settingsManager.setDefaultLightningAddress(s?.toString()?.trim() ?: "")
                }
            }
        })

        // Threshold display - click to show edit dialog
        thresholdDisplay.setOnClickListener {
            showThresholdEditDialog()
        }

        // Percentage slider with haptic feedback
        percentageSlider.addOnChangeListener { slider, value, fromUser ->
            val percentage = value.toInt()
            percentageBadge.text = "$percentage%"
            
            if (fromUser && !isUpdatingUI) {
                settingsManager.setDefaultPercentage(percentage)
                // Subtle haptic on step changes
                slider.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        
        // Manual withdraw row
        manualWithdrawRow.setOnClickListener {
            showMintSelectionDialog()
        }
    }
    
    /**
     * Show a mint selection bottom sheet for manual withdrawal.
     * Displays mints with balances, allowing user to select which to withdraw from.
     */
    private fun showMintSelectionDialog() {
        lifecycleScope.launch {
            // Fetch mint balances
            val balances = withContext(Dispatchers.IO) {
                CashuWalletManager.getAllMintBalances()
            }
            
            // Filter mints with positive balance
            val mintsWithBalance = balances.filter { it.value > 0 }
            
            if (mintsWithBalance.isEmpty()) {
                // No balance to withdraw - show a nice toast instead of dialog
                Toast.makeText(
                    this@AutoWithdrawSettingsActivity,
                    R.string.manual_withdraw_no_balance,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            
            // Show beautiful bottom sheet
            val bottomSheet = MintSelectionBottomSheet.newInstance(
                mintBalances = mintsWithBalance,
                listener = object : MintSelectionBottomSheet.OnMintSelectedListener {
                    override fun onMintSelected(mintUrl: String, balance: Long) {
                        openWithdrawScreen(mintUrl, balance)
                    }
                }
            )
            bottomSheet.show(supportFragmentManager, "MintSelectionBottomSheet")
        }
    }
    
    /**
     * Open the withdraw screen for the selected mint
     */
    private fun openWithdrawScreen(mintUrl: String, balance: Long) {
        val intent = Intent(this, WithdrawLightningActivity::class.java).apply {
            putExtra("mint_url", mintUrl)
            putExtra("balance", balance)
        }
        startActivity(intent)
    }
    
    private fun showThresholdEditDialog() {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.auto_withdraw_threshold_title),
                description = getString(R.string.auto_withdraw_threshold_subtitle),
                hint = "50000",
                initialValue = currentThreshold.toString(),
                prefix = "₿",
                helperText = getString(R.string.auto_withdraw_threshold_helper),
                inputType = android.text.InputType.TYPE_CLASS_NUMBER,
                saveText = getString(R.string.common_save),
                onSave = { value ->
                    val newThreshold = value.replace(",", "").toLongOrNull()
                        ?: AutoWithdrawSettingsManager.DEFAULT_THRESHOLD_SATS
                    currentThreshold = newThreshold.coerceIn(
                        AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS,
                        AutoWithdrawSettingsManager.MAX_THRESHOLD_SATS
                    )
                    settingsManager.setDefaultThreshold(currentThreshold)
                    updateThresholdDisplay()
                },
                validator = { value ->
                    val amount = value.replace(",", "").toLongOrNull()
                    amount != null && amount >= AutoWithdrawSettingsManager.MIN_THRESHOLD_SATS 
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
        updateStatusIndicator(enabled)
        updateConfigFieldsEnabled(enabled)

        lightningAddressInput.setText(settingsManager.getDefaultLightningAddress())
        
        currentThreshold = settingsManager.getDefaultThreshold()
        updateThresholdDisplay()

        val percentage = settingsManager.getDefaultPercentage()
        percentageSlider.value = percentage.toFloat()
        percentageBadge.text = "$percentage%"

        isUpdatingUI = false
    }

    private fun updateStatusIndicator(enabled: Boolean) {
        if (enabled) {
            statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.color_success_green)
            statusText.text = getString(R.string.auto_withdraw_status_active)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))
            statusContainer.background = ContextCompat.getDrawable(this, R.drawable.bg_status_pill_success)
        } else {
            statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.color_text_tertiary)
            statusText.text = getString(R.string.auto_withdraw_status_inactive)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
            statusContainer.background = ContextCompat.getDrawable(this, R.drawable.bg_input_pill)
        }
    }

    private fun animateStatusChange(enabled: Boolean) {
        // Pulse animation on status container
        val scaleX = ObjectAnimator.ofFloat(statusContainer, "scaleX", 1f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(statusContainer, "scaleY", 1f, 1.1f, 1f)
        
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 300
            interpolator = OvershootInterpolator()
            start()
        }

        // Icon pulse
        if (enabled) {
            startIconPulseAnimation()
        } else {
            stopIconPulseAnimation()
        }
    }

    private fun startIconPulseAnimation() {
        iconAnimator?.cancel()
        
        iconAnimator = ObjectAnimator.ofFloat(heroIconContainer, "alpha", 1f, 0.6f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopIconPulseAnimation() {
        iconAnimator?.cancel()
        heroIconContainer.alpha = 1f
    }

    private fun updateConfigFieldsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        
        // Animate alpha change
        lightningAddressInput.animate().alpha(alpha).setDuration(200).start()
        thresholdDisplay.animate().alpha(alpha).setDuration(200).start()
        percentageSlider.animate().alpha(alpha).setDuration(200).start()
        percentageBadge.animate().alpha(alpha).setDuration(200).start()
        
        lightningAddressInput.isEnabled = enabled
        thresholdDisplay.isEnabled = enabled
        thresholdDisplay.isClickable = enabled
        percentageSlider.isEnabled = enabled
    }

    private fun loadHistory() {
        val history = autoWithdrawManager.getHistory()

        if (history.isEmpty()) {
            historyEmptyContainer.visibility = View.VISIBLE
            historyRecyclerView.visibility = View.GONE
            seeAllButton.visibility = View.GONE
        } else {
            historyEmptyContainer.visibility = View.GONE
            historyRecyclerView.visibility = View.VISIBLE
            seeAllButton.visibility = if (history.size > 5) View.VISIBLE else View.GONE
            
            // Show only latest 5 entries
            val displayHistory = history.take(5)
            historyRecyclerView.adapter = AutoWithdrawHistoryAdapter(displayHistory)
        }
    }

    private fun startEntranceAnimations() {
        // Hero card slide in
        val heroCard: CardView = findViewById(R.id.hero_card)
        heroCard.alpha = 0f
        heroCard.translationY = -50f
        heroCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Icon bounce
        heroIconContainer.scaleX = 0f
        heroIconContainer.scaleY = 0f
        heroIconContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(200)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // Status pill fade
        statusContainer.alpha = 0f
        statusContainer.animate()
            .alpha(1f)
            .setStartDelay(400)
            .setDuration(300)
            .start()

        // Cards stagger in
        val toggleCard: CardView = findViewById(R.id.toggle_card)
        animateCardEntrance(toggleCard, 100)
        
        val manualWithdrawCard: CardView = findViewById(R.id.manual_withdraw_card)
        animateCardEntrance(manualWithdrawCard, 200)
        
        // If auto-withdraw is enabled, start icon animation
        if (settingsManager.isGloballyEnabled()) {
            heroIconContainer.postDelayed({ startIconPulseAnimation() }, 800)
        }
    }

    private fun animateCardEntrance(card: View, delay: Long) {
        card.alpha = 0f
        card.translationY = 30f
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    override fun onResume() {
        super.onResume()
        // Refresh history when returning to activity (e.g., after completing a withdrawal)
        loadHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        iconAnimator?.cancel()
    }

    /**
     * Premium adapter for displaying auto-withdraw history with expandable error details.
     */
    private inner class AutoWithdrawHistoryAdapter(
        private val entries: List<WithdrawHistoryEntry>
    ) : RecyclerView.Adapter<AutoWithdrawHistoryAdapter.ViewHolder>() {
        
        // Track expanded state for each item
        private val expandedItems = mutableSetOf<String>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconContainer: FrameLayout = view.findViewById(R.id.icon_container)
            val statusIcon: ImageView = view.findViewById(R.id.status_icon)
            val amountText: TextView = view.findViewById(R.id.amount_text)
            val addressText: TextView = view.findViewById(R.id.address_text)
            val mintText: TextView = view.findViewById(R.id.mint_text)
            val timestampText: TextView = view.findViewById(R.id.timestamp_text)
            val statusBadge: TextView = view.findViewById(R.id.status_badge)
            val autoBadge: TextView = view.findViewById(R.id.auto_badge)
            val expandIndicator: ImageView = view.findViewById(R.id.expand_indicator)
            val errorContainer: LinearLayout = view.findViewById(R.id.error_container)
            val errorText: TextView = view.findViewById(R.id.error_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_auto_withdraw_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            // Format amount using Amount class with ₿ symbol
            val amount = Amount(entry.amountSats, Amount.Currency.BTC)
            holder.amountText.text = amount.toString()

            // Destination (address or invoice abbreviation)
            holder.addressText.text = entry.destination.ifBlank { entry.lightningAddress ?: "" }

            // Mint label
            holder.mintText.text = entry.mintUrl

            // Auto/manual badge
            if (entry.automatic) {
                holder.autoBadge.visibility = View.VISIBLE
            } else {
                holder.autoBadge.visibility = View.GONE
            }

            // Relative timestamp
            val dateFormat = SimpleDateFormat("MMM d • HH:mm", Locale.getDefault())
            holder.timestampText.text = dateFormat.format(Date(entry.timestamp))

            // Status styling
            when (entry.status) {
                WithdrawHistoryEntry.STATUS_COMPLETED -> {
                    holder.statusIcon.setImageResource(R.drawable.ic_check)
                    holder.statusIcon.setColorFilter(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_success_green))
                    holder.iconContainer.backgroundTintList = ContextCompat.getColorStateList(this@AutoWithdrawSettingsActivity, R.color.color_bg_secondary)
                    holder.statusBadge.text = getString(R.string.auto_withdraw_status_completed)
                    holder.statusBadge.setTextColor(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_success_green))
                    holder.statusBadge.background = ContextCompat.getDrawable(this@AutoWithdrawSettingsActivity, R.drawable.bg_status_pill_success)
                    holder.expandIndicator.visibility = View.GONE
                    holder.errorContainer.visibility = View.GONE
                }
                WithdrawHistoryEntry.STATUS_PENDING -> {
                    holder.statusIcon.setImageResource(R.drawable.ic_pending)
                    holder.statusIcon.setColorFilter(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_warning))
                    holder.iconContainer.backgroundTintList = ContextCompat.getColorStateList(this@AutoWithdrawSettingsActivity, R.color.color_bg_secondary)
                    holder.statusBadge.text = getString(R.string.auto_withdraw_status_pending)
                    holder.statusBadge.setTextColor(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_warning))
                    holder.statusBadge.background = ContextCompat.getDrawable(this@AutoWithdrawSettingsActivity, R.drawable.bg_status_pill_pending)
                    holder.expandIndicator.visibility = View.GONE
                    holder.errorContainer.visibility = View.GONE
                }
                WithdrawHistoryEntry.STATUS_FAILED -> {
                    holder.statusIcon.setImageResource(R.drawable.ic_close)
                    holder.statusIcon.setColorFilter(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_error))
                    holder.iconContainer.backgroundTintList = ContextCompat.getColorStateList(this@AutoWithdrawSettingsActivity, R.color.color_bg_secondary)
                    holder.statusBadge.text = getString(R.string.auto_withdraw_status_failed)
                    holder.statusBadge.setTextColor(ContextCompat.getColor(this@AutoWithdrawSettingsActivity, R.color.color_error))
                    holder.statusBadge.background = ContextCompat.getDrawable(this@AutoWithdrawSettingsActivity, R.drawable.bg_status_pill_error)
                    
                    // Show expand indicator if there's an error message
                    val hasError = !entry.errorMessage.isNullOrBlank()
                    holder.expandIndicator.visibility = if (hasError) View.VISIBLE else View.GONE
                    
                    // Set error message
                    holder.errorText.text = entry.errorMessage ?: ""
                    
                    // Check if this item is expanded
                    val isExpanded = expandedItems.contains(entry.id)
                    updateExpandState(holder, isExpanded, animate = false)
                    
                    // Set click listener to toggle expansion
                    if (hasError) {
                        holder.itemView.setOnClickListener {
                            toggleExpand(entry.id, holder)
                        }
                    } else {
                        holder.itemView.setOnClickListener(null)
                    }
                }
            }

            // Animate item appearance
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .alpha(1f)
                .setStartDelay((position * 50).toLong())
                .setDuration(200)
                .start()
        }
        
        private fun toggleExpand(entryId: String, holder: ViewHolder) {
            val isCurrentlyExpanded = expandedItems.contains(entryId)
            if (isCurrentlyExpanded) {
                expandedItems.remove(entryId)
            } else {
                expandedItems.add(entryId)
            }
            updateExpandState(holder, !isCurrentlyExpanded, animate = true)
        }
        
        private fun updateExpandState(holder: ViewHolder, isExpanded: Boolean, animate: Boolean) {
            if (animate) {
                // Rotate expand indicator
                val targetRotation = if (isExpanded) 180f else 0f
                holder.expandIndicator.animate()
                    .rotation(targetRotation)
                    .setDuration(200)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
                
                // Animate error container
                if (isExpanded) {
                    holder.errorContainer.visibility = View.VISIBLE
                    holder.errorContainer.alpha = 0f
                    holder.errorContainer.translationY = -10f
                    holder.errorContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(200)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    holder.errorContainer.animate()
                        .alpha(0f)
                        .translationY(-10f)
                        .setDuration(150)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            holder.errorContainer.visibility = View.GONE
                        }
                        .start()
                }
            } else {
                // Instant update without animation
                holder.expandIndicator.rotation = if (isExpanded) 180f else 0f
                holder.errorContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
                holder.errorContainer.alpha = if (isExpanded) 1f else 0f
            }
        }

        override fun getItemCount() = entries.size
    }
}
