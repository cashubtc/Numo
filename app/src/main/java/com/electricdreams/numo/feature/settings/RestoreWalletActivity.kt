package com.electricdreams.numo.feature.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.nostr.NostrMintBackup
import com.electricdreams.numo.ui.seed.SeedWordEditText
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Activity for restoring wallet from a 12-word seed phrase.
 * 
 * Enhanced multi-step flow:
 * 1. Enter seed phrase
 * 2. Fetch mints from Nostr backup (if available)
 * 3. Review and confirm mints to restore
 * 4. Perform restore with progress
 * 5. Show success summary
 */
class RestoreWalletActivity : AppCompatActivity() {

    // === UI State ===
    private enum class RestoreStep {
        ENTER_SEED,
        FETCHING_BACKUP,
        REVIEW_MINTS,
        RESTORING,
        SUCCESS
    }

    private var currentStep = RestoreStep.ENTER_SEED

    // === Views ===
    // Top bar
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageView

    // Step 1: Seed entry
    private lateinit var seedEntryContainer: View
    private lateinit var seedInputGrid: GridLayout
    private lateinit var pasteButton: MaterialButton
    private lateinit var continueButton: MaterialButton
    private lateinit var validationStatus: LinearLayout
    private lateinit var validationIcon: ImageView
    private lateinit var validationText: TextView

    // Step 2: Fetching backup
    private lateinit var fetchingOverlay: FrameLayout
    private lateinit var fetchingStatus: TextView

    // Step 3: Review mints
    private lateinit var reviewMintsContainer: View
    private lateinit var backupStatusCard: LinearLayout
    private lateinit var backupStatusIcon: ImageView
    private lateinit var backupStatusTitle: TextView
    private lateinit var backupStatusSubtitle: TextView
    private lateinit var mintsListContainer: LinearLayout
    private lateinit var mintsCountText: TextView
    private lateinit var startRestoreButton: MaterialButton

    // Step 4: Restoring
    private lateinit var progressOverlay: FrameLayout
    private lateinit var progressStatus: TextView
    private lateinit var mintProgressContainer: LinearLayout

    // Step 5: Success
    private lateinit var successOverlay: FrameLayout
    private lateinit var balanceChangesContainer: LinearLayout
    private lateinit var successSummaryText: TextView
    private lateinit var doneButton: MaterialButton

    // === Data ===
    private val seedInputs = mutableListOf<SeedWordEditText>()
    private val mintProgressViews = mutableMapOf<String, View>()

    // Mints discovered from backup + existing configured mints
    private val discoveredMints = mutableSetOf<String>()
    private val selectedMints = mutableSetOf<String>()
    private var backupTimestamp: Long? = null
    private var backupFound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restore_wallet)

        initViews()
        setupSeedInputs()
        setupListeners()
        updateUIForStep(RestoreStep.ENTER_SEED)
    }

    private fun initViews() {
        // Top bar
        titleText = findViewById(R.id.title_text)
        backButton = findViewById(R.id.back_button)

        // Step 1: Seed entry
        seedEntryContainer = findViewById(R.id.seed_entry_container)
        seedInputGrid = findViewById(R.id.seed_input_grid)
        pasteButton = findViewById(R.id.paste_button)
        continueButton = findViewById(R.id.continue_button)
        validationStatus = findViewById(R.id.validation_status)
        validationIcon = findViewById(R.id.validation_icon)
        validationText = findViewById(R.id.validation_text)

        // Step 2: Fetching backup
        fetchingOverlay = findViewById(R.id.fetching_overlay)
        fetchingStatus = findViewById(R.id.fetching_status)

        // Step 3: Review mints
        reviewMintsContainer = findViewById(R.id.review_mints_container)
        backupStatusCard = findViewById(R.id.backup_status_card)
        backupStatusIcon = findViewById(R.id.backup_status_icon)
        backupStatusTitle = findViewById(R.id.backup_status_title)
        backupStatusSubtitle = findViewById(R.id.backup_status_subtitle)
        mintsListContainer = findViewById(R.id.mints_list_container)
        mintsCountText = findViewById(R.id.mints_count_text)
        startRestoreButton = findViewById(R.id.start_restore_button)

        // Step 4: Restoring
        progressOverlay = findViewById(R.id.restore_progress_overlay)
        progressStatus = findViewById(R.id.restore_progress_status)
        mintProgressContainer = findViewById(R.id.mint_progress_container)

        // Step 5: Success
        successOverlay = findViewById(R.id.restore_success_overlay)
        balanceChangesContainer = findViewById(R.id.balance_changes_container)
        successSummaryText = findViewById(R.id.success_summary_text)
        doneButton = findViewById(R.id.done_button)
    }

    private fun setupSeedInputs() {
        seedInputGrid.removeAllViews()
        seedInputs.clear()

        for (i in 0 until 12) {
            val inputContainer = createSeedInputView(i + 1)

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(i % 2, 1f)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            }
            inputContainer.layoutParams = params

            seedInputGrid.addView(inputContainer)
        }
    }

    private fun createSeedInputView(index: Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_seed_input)
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }

        val indexText = TextView(this).apply {
            text = "$index"
            setTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER
        }

        val input = SeedWordEditText(this).apply {
            hint = ""
            setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            background = null
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = if (index == 12) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8.dpToPx()
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    validateInputs()
                }
            })

            setOnFocusChangeListener { _, hasFocus ->
                container.background = ContextCompat.getDrawable(
                    context,
                    if (hasFocus) R.drawable.bg_seed_input_focused else R.drawable.bg_seed_input
                )
            }

            // Allow pasting an entire seed phrase into a single cell. When
            // multi-word text is detected, distribute it across all 12
            // fields (or show a toast if we cannot).
            onSeedPasteListener = { pastedText ->
                handleSeedPhrasePaste(pastedText)
            }
        }

        seedInputs.add(input)

        container.addView(indexText)
        container.addView(input)

        return container
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            handleBackPress()
        }

        pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        continueButton.setOnClickListener {
            proceedToFetchBackup()
        }

        startRestoreButton.setOnClickListener {
            showRestoreConfirmationDialog()
        }

        doneButton.setOnClickListener {
            finish()
        }
    }

    private fun handleBackPress() {
        when (currentStep) {
            RestoreStep.ENTER_SEED -> finish()
            RestoreStep.REVIEW_MINTS -> {
                // Go back to seed entry
                updateUIForStep(RestoreStep.ENTER_SEED)
            }
            RestoreStep.FETCHING_BACKUP,
            RestoreStep.RESTORING,
            RestoreStep.SUCCESS -> {
                // Don't allow back during these states
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPress()
    }

    private fun updateUIForStep(step: RestoreStep) {
        currentStep = step

        // Hide all containers first
        seedEntryContainer.visibility = View.GONE
        fetchingOverlay.visibility = View.GONE
        reviewMintsContainer.visibility = View.GONE
        progressOverlay.visibility = View.GONE
        successOverlay.visibility = View.GONE

        when (step) {
            RestoreStep.ENTER_SEED -> {
                titleText.text = getString(R.string.restore_progress_title)
                seedEntryContainer.visibility = View.VISIBLE
                backButton.isEnabled = true
            }
            RestoreStep.FETCHING_BACKUP -> {
                titleText.text = getString(R.string.restore_fetching_title)
                fetchingOverlay.visibility = View.VISIBLE
                backButton.isEnabled = false
            }
            RestoreStep.REVIEW_MINTS -> {
                titleText.text = getString(R.string.restore_mints_title)
                reviewMintsContainer.visibility = View.VISIBLE
                backButton.isEnabled = true
                updateMintsUI()
            }
            RestoreStep.RESTORING -> {
                titleText.text = getString(R.string.restore_progress_title)
                progressOverlay.visibility = View.VISIBLE
                backButton.isEnabled = false
            }
            RestoreStep.SUCCESS -> {
                titleText.text = getString(R.string.restore_success_title)
                successOverlay.visibility = View.VISIBLE
                backButton.isEnabled = false
            }
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(this, getString(R.string.onboarding_clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val pastedText = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
        handleSeedPhrasePaste(pastedText)
    }

    /**
     * Handle a seed phrase paste operation.
     *
     * This method is shared by both the dedicated "Paste from Clipboard"
     * button and direct pastes into individual seed word fields.
     *
     * @param pastedText Raw text from the clipboard.
     * @return `true` if the paste was handled and default insertion should be
     * suppressed, `false` otherwise.
     */
    private fun handleSeedPhrasePaste(pastedText: String): Boolean {
        val words = pastedText
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        // We only support full 12-word phrases. If the clipboard content
        // cannot be mapped cleanly, show a friendly error and leave the
        // existing input unchanged.
        if (words.size != 12) {
            Toast.makeText(this, getString(R.string.onboarding_seed_paste_invalid), Toast.LENGTH_LONG).show()
            return true
        }

        // Distribute all 12 words across the grid, regardless of which
        // individual cell triggered the paste.
        words.forEachIndexed { index, word ->
            if (index < seedInputs.size) {
                seedInputs[index].setText(word.lowercase(Locale.getDefault()))
            }
        }

        validateInputs()
        Toast.makeText(this, getString(R.string.onboarding_seed_paste_success), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun validateInputs(): Boolean {
        val words = seedInputs.map { it.text.toString().trim().lowercase() }
        val filledCount = words.count { it.isNotBlank() }
        val allFilled = filledCount == 12
        val allValid = words.all { it.isBlank() || it.matches(Regex("^[a-z]+$")) }

        // Update validation status
        when {
            filledCount == 0 -> {
                validationStatus.visibility = View.GONE
            }
            !allValid -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_close)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning_red))
                validationText.text = getString(R.string.onboarding_seed_invalid_characters)
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning_red))
            }
            !allFilled -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_warning)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning))
                validationText.text = getString(R.string.onboarding_seed_words_entered_count, filledCount)
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning))
            }
            else -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_check)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))
                validationText.text = getString(R.string.onboarding_seed_validation_ready)
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))
            }
        }

        // Update continue button state
        val canContinue = allFilled && allValid
        continueButton.isEnabled = canContinue
        continueButton.alpha = if (canContinue) 1f else 0.5f

        return canContinue
    }

    private fun getMnemonic(): String {
        return seedInputs.map { it.text.toString().trim().lowercase() }.joinToString(" ")
    }

    private fun proceedToFetchBackup() {
        if (!validateInputs()) return

        val mnemonic = getMnemonic()

        // Show fetching overlay
        updateUIForStep(RestoreStep.FETCHING_BACKUP)
        fetchingStatus.text = getString(R.string.onboarding_fetching_searching_backup)

        lifecycleScope.launch {
            // Fetch backup from Nostr
            val result = withContext(Dispatchers.IO) {
                fetchMintBackupSuspend(mnemonic)
            }

            // Get existing configured mints
            val mintManager = MintManager.getInstance(this@RestoreWalletActivity)
            val existingMints = mintManager.getAllowedMints()

            // Reset mint sets
            discoveredMints.clear()
            selectedMints.clear()

            if (result.success && result.mints.isNotEmpty()) {
                // Backup found! Add discovered mints
                backupFound = true
                backupTimestamp = result.timestamp
                discoveredMints.addAll(result.mints)
                selectedMints.addAll(result.mints)
            } else {
                // No backup found, use existing mints
                backupFound = false
                backupTimestamp = null
            }

            // Also include existing configured mints
            discoveredMints.addAll(existingMints)
            selectedMints.addAll(existingMints)

            // Proceed to review screen
            withContext(Dispatchers.Main) {
                updateUIForStep(RestoreStep.REVIEW_MINTS)
            }
        }
    }

    private suspend fun fetchMintBackupSuspend(mnemonic: String): NostrMintBackup.FetchResult {
        return suspendCancellableCoroutine { continuation ->
            NostrMintBackup.fetchMintBackup(mnemonic) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }

    private fun updateMintsUI() {
        // Update backup status card
        if (backupFound) {
            backupStatusCard.background = ContextCompat.getDrawable(this, R.drawable.bg_success_card)
            backupStatusIcon.setImageResource(R.drawable.ic_cloud_done)
            backupStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))
            backupStatusTitle.text = getString(R.string.restore_backup_found_title)
            backupStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))

            val dateStr = backupTimestamp?.let {
                SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(it * 1000))
            } ?: getString(R.string.restore_backup_unknown_date)
            backupStatusSubtitle.text = getString(R.string.restore_backup_found_subtitle, dateStr)
            backupStatusSubtitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        } else {
            backupStatusCard.background = ContextCompat.getDrawable(this, R.drawable.bg_info_card)
            backupStatusIcon.setImageResource(R.drawable.ic_cloud_off)
            backupStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_text_tertiary))
            backupStatusTitle.text = getString(R.string.onboarding_backup_not_found_title)
            backupStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
            backupStatusSubtitle.text = getString(R.string.onboarding_backup_not_found_subtitle)
            backupStatusSubtitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        }

        // Update mints list
        mintsListContainer.removeAllViews()

        val sortedMints = discoveredMints.sortedBy { extractMintName(it).lowercase() }

        for (mintUrl in sortedMints) {
            val mintView = createMintItemView(mintUrl, selectedMints.contains(mintUrl))
            mintsListContainer.addView(mintView)
        }

        updateMintsCount()
    }

    private fun createMintItemView(mintUrl: String, isSelected: Boolean): View {
        val mintManager = MintManager.getInstance(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = ContextCompat.getDrawable(context, R.drawable.bg_mint_item)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        }

        // Checkbox
        val checkbox = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                marginEnd = 14.dpToPx()
            }
            updateCheckboxState(this, isSelected)
        }

        // Mint info container
        val infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val displayName = mintManager.getMintDisplayName(mintUrl)
        val nameText = TextView(this).apply {
            text = displayName
            setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val urlText = TextView(this).apply {
            text = mintUrl.removePrefix("https://")
            setTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        infoContainer.addView(nameText)
        infoContainer.addView(urlText)

        container.addView(checkbox)
        container.addView(infoContainer)

        // Toggle selection on click
        container.setOnClickListener {
            val nowSelected = !selectedMints.contains(mintUrl)
            if (nowSelected) {
                selectedMints.add(mintUrl)
            } else {
                selectedMints.remove(mintUrl)
            }
            updateCheckboxState(checkbox, nowSelected)
            updateMintsCount()
        }

        return container
    }

    private fun updateCheckboxState(checkbox: ImageView, isSelected: Boolean) {
        if (isSelected) {
            checkbox.setImageResource(R.drawable.ic_checkbox_checked)
            checkbox.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))
        } else {
            checkbox.setImageResource(R.drawable.ic_checkbox_unchecked)
            checkbox.setColorFilter(ContextCompat.getColor(this, R.color.color_text_tertiary))
        }
    }

    private fun updateMintsCount() {
        val count = selectedMints.size
        mintsCountText.text = getString(R.string.onboarding_mints_count, count, if (count != 1) "s" else "")

        // Update button state
        startRestoreButton.isEnabled = count > 0
        startRestoreButton.alpha = if (count > 0) 1f else 0.5f
    }

    private fun showRestoreConfirmationDialog() {
        if (selectedMints.isEmpty()) {
            Toast.makeText(this, getString(R.string.onboarding_error_initializing_wallet), Toast.LENGTH_SHORT).show()
            return
        }

        val currentMnemonic = CashuWalletManager.getMnemonic()
        val mintCount = selectedMints.size

        val message = buildString {
            append("This will restore your wallet from the entered seed phrase.\n\n")
            append("⚠️ IMPORTANT:\n")
            append("• Make sure you have backed up your current seed phrase\n")
            append("• This action cannot be undone\n")
            append("• Restore will be attempted for $mintCount selected mint(s)\n\n")
            if (currentMnemonic != null) {
                append("Your current seed phrase starts with: \"${currentMnemonic.split(" ").take(3).joinToString(" ")}...\"")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.restore_confirm_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.restore_confirm_dialog_positive) { _, _ ->
                performRestore()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun performRestore() {
        val mnemonic = getMnemonic()

        // Show progress overlay
        updateUIForStep(RestoreStep.RESTORING)
        mintProgressContainer.visibility = View.VISIBLE
        mintProgressContainer.removeAllViews()
        mintProgressViews.clear()

        // First, update the MintManager with selected mints
        val mintManager = MintManager.getInstance(this)

        // Add any new mints that weren't previously configured
        for (mintUrl in selectedMints) {
            if (!mintManager.isMintAllowed(mintUrl)) {
                mintManager.addMint(mintUrl)
            }
        }

        // Initialize mint progress items for selected mints
        selectedMints.forEach { mintUrl ->
            val progressView = createMintProgressView(mintUrl)
            mintProgressContainer.addView(progressView)
            mintProgressViews[mintUrl] = progressView
        }

        lifecycleScope.launch {
            try {
                progressStatus.text = getString(R.string.onboarding_restoring_initializing)

                // Create custom restore that only uses selected mints
                val balanceChanges = restoreWithSelectedMints(mnemonic, selectedMints.toList())

                withContext(Dispatchers.Main) {
                    showSuccess(balanceChanges)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateUIForStep(RestoreStep.REVIEW_MINTS)
                    Toast.makeText(
                        this@RestoreWalletActivity,
                        "Restore failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun restoreWithSelectedMints(
        mnemonic: String,
        mints: List<String>
    ): Map<String, Pair<Long, Long>> {
        return CashuWalletManager.restoreFromMnemonic(mnemonic) { mintUrl, status, balanceBefore, balanceAfter ->
            // Only show progress for selected mints
            if (mints.contains(mintUrl)) {
                withContext(Dispatchers.Main) {
                    updateMintProgress(mintUrl, status, balanceBefore, balanceAfter)
                }
            }
        }
    }

    private fun createMintProgressView(mintUrl: String): View {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_mint_restore_progress, mintProgressContainer, false)

        val mintName = view.findViewById<TextView>(R.id.mint_name)
        val mintManager = MintManager.getInstance(this)
        mintName.text = mintManager.getMintDisplayName(mintUrl)

        return view
    }

    private fun updateMintProgress(mintUrl: String, status: String, balanceBefore: Long, balanceAfter: Long) {
        val view = mintProgressViews[mintUrl] ?: return

        val spinner = view.findViewById<ProgressBar>(R.id.mint_spinner)
        val statusIcon = view.findViewById<ImageView>(R.id.mint_status_icon)
        val mintStatus = view.findViewById<TextView>(R.id.mint_status)
        val balanceChange = view.findViewById<TextView>(R.id.balance_change)

        mintStatus.text = status

        when {
            status == "Complete" -> {
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_check)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))

                val diff = balanceAfter - balanceBefore
                if (diff != 0L) {
                    balanceChange.visibility = View.VISIBLE
                    balanceChange.text = if (diff >= 0) "+$diff sats" else "$diff sats"
                    balanceChange.setTextColor(
                        ContextCompat.getColor(
                            this,
                            if (diff >= 0) R.color.color_success_green else R.color.color_warning_red
                        )
                    )
                }
            }
            status.startsWith("Failed") -> {
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_close)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning_red))
            }
            else -> {
                spinner.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
            }
        }
    }

    private fun showSuccess(balanceChanges: Map<String, Pair<Long, Long>>) {
        updateUIForStep(RestoreStep.SUCCESS)

        // Filter to only selected mints
        val relevantChanges = balanceChanges.filterKeys { selectedMints.contains(it) }

        // Calculate totals
        val totalBefore = relevantChanges.values.sumOf { it.first }
        val totalAfter = relevantChanges.values.sumOf { it.second }
        val totalDiff = totalAfter - totalBefore

        successSummaryText.text = when {
            totalDiff > 0 -> getString(R.string.restore_success_recovered_summary, totalDiff, relevantChanges.size)
            totalDiff < 0 -> getString(R.string.restore_success_balance_changed_summary, totalDiff)
            else -> getString(R.string.restore_success_restored_with_balance_summary, totalAfter)
        }

        // Show balance changes per mint
        balanceChangesContainer.removeAllViews()
        relevantChanges.forEach { (mintUrl, balances) ->
            val (before, after) = balances
            val diff = after - before

            val itemView = createBalanceChangeItem(mintUrl, before, after, diff)
            balanceChangesContainer.addView(itemView)
        }
    }

    private fun createBalanceChangeItem(mintUrl: String, before: Long, after: Long, diff: Long): View {
        val mintManager = MintManager.getInstance(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = ContextCompat.getDrawable(context, R.drawable.bg_mint_item)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        }

        val infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(this).apply {
            text = mintManager.getMintDisplayName(mintUrl)
            setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        val detailText = TextView(this).apply {
            text = getString(R.string.restore_success_balance_change_line, before, after)
            setTextColor(ContextCompat.getColor(context, R.color.color_text_secondary))
            textSize = 13f
        }

        infoContainer.addView(nameText)
        infoContainer.addView(detailText)

        val diffText = TextView(this).apply {
            text = if (diff >= 0) "+$diff" else "$diff"
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        diff > 0 -> R.color.color_success_green
                        diff < 0 -> R.color.color_warning_red
                        else -> R.color.color_text_secondary
                    }
                )
            )
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        container.addView(infoContainer)
        container.addView(diffText)

        return container
    }

    private fun extractMintName(mintUrl: String): String {
        return try {
            val url = java.net.URL(mintUrl)
            url.host
        } catch (e: Exception) {
            mintUrl.take(30)
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
