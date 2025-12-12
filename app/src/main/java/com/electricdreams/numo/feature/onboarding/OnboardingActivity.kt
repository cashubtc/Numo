package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.ModernPOSActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.nostr.NostrMintBackup
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.cashudevkit.generateMnemonic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Onboarding Activity - First-time user experience.
 * 
 * Flow:
 * 1. Welcome screen with Terms of Service
 * 2. Create new wallet OR Restore from seed phrase
 * 3. For new wallet: Generate seed phrase and add default mints
 *    For restore: Enter seed phrase → Fetch backup from Nostr → Add mints → Restore balances
 * 4. Review mints screen
 * 5. Success screen → Enter wallet
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "OnboardingPrefs"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

        fun isOnboardingComplete(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        }

        fun setOnboardingComplete(context: Context, complete: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()
        }
    }

    // === State ===
    private enum class OnboardingStep {
        WELCOME,
        CHOOSE_PATH,
        ENTER_SEED,          // Restore flow only
        FETCHING_BACKUP,     // Restore flow only
        GENERATING_WALLET,   // New wallet flow only
        REVIEW_MINTS,
        RESTORING,           // Restore flow only
        SUCCESS
    }

    private var currentStep = OnboardingStep.WELCOME
    private var isRestoreFlow = false

    // === Data ===
    private var generatedMnemonic: String? = null
    private var enteredMnemonic: String? = null
    private val discoveredMints = mutableSetOf<String>()
    private val selectedMints = mutableSetOf<String>()
    private var backupFound = false
    private var backupTimestamp: Long? = null
    private val balanceChanges = mutableMapOf<String, Pair<Long, Long>>()

    // === Views ===
    // Step 1: Welcome
    private lateinit var welcomeContainer: FrameLayout
    private lateinit var welcomeBackgroundOverlay: View
    private lateinit var welcomeNavyCurve: ImageView
    private lateinit var welcomeWordmark: ImageView
    private lateinit var welcomeTagline: TextView
    private lateinit var termsText: TextView
    private lateinit var acceptButton: MaterialButton

    // Step 2: Choose Path
    private lateinit var choosePathContainer: FrameLayout
    private lateinit var createWalletButton: View
    private lateinit var restoreWalletButton: View

    // Step 3: Enter Seed (Restore)
    private lateinit var enterSeedContainer: FrameLayout
    private lateinit var seedInputGrid: GridLayout
    private lateinit var pasteButton: MaterialButton
    private lateinit var seedContinueButton: MaterialButton
    private lateinit var seedValidationStatus: LinearLayout
    private lateinit var seedValidationIcon: ImageView
    private lateinit var seedValidationText: TextView
    private lateinit var seedBackButton: ImageView

    // Step 4a: Generating Wallet (New)
    private lateinit var generatingContainer: FrameLayout
    private lateinit var generatingStatus: TextView

    // Step 4b: Fetching Backup (Restore)
    private lateinit var fetchingContainer: FrameLayout
    private lateinit var fetchingStatus: TextView

    // Step 5: Review Mints
    private lateinit var reviewMintsContainer: FrameLayout
    private lateinit var backupStatusCard: LinearLayout
    private lateinit var backupStatusIcon: ImageView
    private lateinit var backupStatusTitle: TextView
    private lateinit var backupStatusSubtitle: TextView
    private lateinit var mintsListContainer: LinearLayout
    private lateinit var mintsCountText: TextView
    private lateinit var mintsSubtitle: TextView
    private lateinit var mintsContinueButton: MaterialButton
    private lateinit var mintsBackButton: ImageView

    // Step 6: Restoring (Restore flow)
    private lateinit var restoringContainer: FrameLayout
    private lateinit var restoringStatus: TextView
    private lateinit var mintProgressContainer: LinearLayout

    // Step 7: Success
    private lateinit var successContainer: FrameLayout
    private lateinit var successTitle: TextView
    private lateinit var successSubtitle: TextView
    private lateinit var balanceChangesContainer: LinearLayout
    private lateinit var successBalanceSection: LinearLayout
    private lateinit var enterWalletButton: MaterialButton

    // Seed input helpers
    private val seedInputs = mutableListOf<EditText>()
    private val mintProgressViews = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // CRITICAL: Force light mode for onboarding - must be before super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        
        super.onCreate(savedInstanceState)

        // Check if onboarding is already complete - redirect to main app
        if (isOnboardingComplete(this)) {
            val intent = Intent(this, ModernPOSActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        setupWindow()
        initViews()
        setupListeners()
        showStep(OnboardingStep.WELCOME)
    }

    private fun setupWindow() {
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Default to white/light bars (will be updated per screen)
        updateWindowBarsForStep(OnboardingStep.WELCOME)

        // Apply insets as padding to content, but don't consume them
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }
    }

    /**
     * Updates the status bar and navigation bar colors based on the current onboarding step.
     * Welcome screen: Navy status bar (top matches navy curve), green nav bar (bottom matches green).
     * All other screens: White/light bars.
     */
    private fun updateWindowBarsForStep(step: OnboardingStep) {
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        
        if (step == OnboardingStep.WELCOME) {
            // Navy status bar (matches curved navy section at top)
            val navyColor = android.graphics.Color.parseColor("#0A2540")
            val greenColor = android.graphics.Color.parseColor("#5EFFC2")
            window.statusBarColor = navyColor
            window.navigationBarColor = greenColor
            
            // Light icons on navy status bar, dark icons on green nav bar
            windowInsetsController.isAppearanceLightStatusBars = false
            windowInsetsController.isAppearanceLightNavigationBars = false
        } else {
            // White/light bars for all other screens
            val bgColor = android.graphics.Color.parseColor("#F6F7F8")
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
            
            // Dark icons on light background
            windowInsetsController.isAppearanceLightStatusBars = true
            windowInsetsController.isAppearanceLightNavigationBars = true
        }
    }

    private fun initViews() {
        // Welcome
        welcomeContainer = findViewById(R.id.welcome_container)
        welcomeBackgroundOverlay = findViewById(R.id.welcome_background_overlay)
        welcomeNavyCurve = findViewById(R.id.welcome_navy_curve)
        welcomeWordmark = findViewById(R.id.welcome_wordmark)
        welcomeTagline = findViewById(R.id.welcome_tagline)
        termsText = findViewById(R.id.terms_text)
        acceptButton = findViewById(R.id.accept_button)

        // Choose Path
        choosePathContainer = findViewById(R.id.choose_path_container)
        createWalletButton = findViewById(R.id.create_wallet_button)
        restoreWalletButton = findViewById(R.id.restore_wallet_button)

        // Enter Seed
        enterSeedContainer = findViewById(R.id.enter_seed_container)
        seedInputGrid = findViewById(R.id.seed_input_grid)
        pasteButton = findViewById(R.id.paste_button)
        seedContinueButton = findViewById(R.id.seed_continue_button)
        seedValidationStatus = findViewById(R.id.seed_validation_status)
        seedValidationIcon = findViewById(R.id.seed_validation_icon)
        seedValidationText = findViewById(R.id.seed_validation_text)
        seedBackButton = findViewById(R.id.seed_back_button)

        // Generating Wallet
        generatingContainer = findViewById(R.id.generating_container)
        generatingStatus = findViewById(R.id.generating_status)

        // Fetching Backup
        fetchingContainer = findViewById(R.id.fetching_container)
        fetchingStatus = findViewById(R.id.fetching_status)

        // Review Mints
        reviewMintsContainer = findViewById(R.id.review_mints_container)
        backupStatusCard = findViewById(R.id.backup_status_card)
        backupStatusIcon = findViewById(R.id.backup_status_icon)
        backupStatusTitle = findViewById(R.id.backup_status_title)
        backupStatusSubtitle = findViewById(R.id.backup_status_subtitle)
        mintsListContainer = findViewById(R.id.mints_list_container)
        mintsCountText = findViewById(R.id.mints_count_text)
        mintsSubtitle = findViewById(R.id.mints_subtitle)
        mintsContinueButton = findViewById(R.id.mints_continue_button)
        mintsBackButton = findViewById(R.id.mints_back_button)

        // Restoring
        restoringContainer = findViewById(R.id.restoring_container)
        restoringStatus = findViewById(R.id.restoring_status)
        mintProgressContainer = findViewById(R.id.mint_progress_container)

        // Success
        successContainer = findViewById(R.id.success_container)
        successTitle = findViewById(R.id.success_title)
        successSubtitle = findViewById(R.id.success_subtitle)
        balanceChangesContainer = findViewById(R.id.balance_changes_container)
        successBalanceSection = findViewById(R.id.success_balance_section)
        enterWalletButton = findViewById(R.id.enter_wallet_button)

        // Setup terms text with clickable link
        setupTermsText()

        // Setup seed inputs
        setupSeedInputs()
    }

    private fun setupTermsText() {
        val fullText = getString(R.string.onboarding_terms_text)
        val spannableString = SpannableString(fullText)

        val termsLabel = "Terms of Service"
        val termsStart = fullText.indexOf(termsLabel)
        if (termsStart != -1) {
            val termsEnd = termsStart + termsLabel.length

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    showTermsDialog()
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(this@OnboardingActivity, R.color.color_accent_blue)
                    ds.isUnderlineText = true
                }
            }

            spannableString.setSpan(clickableSpan, termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        termsText.text = spannableString
        termsText.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun showTermsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_terms_title)
            .setMessage(getString(R.string.dialog_terms_body))
            .setPositiveButton(R.string.common_close, null)
            .show()
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

        val input = EditText(this).apply {
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
                    validateSeedInputs()
                }
            })

            setOnFocusChangeListener { _, hasFocus ->
                container.background = ContextCompat.getDrawable(
                    context,
                    if (hasFocus) R.drawable.bg_seed_input_focused else R.drawable.bg_seed_input
                )
            }
        }

        seedInputs.add(input)

        container.addView(indexText)
        container.addView(input)

        return container
    }

    private fun setupListeners() {
        // Welcome
        acceptButton.setOnClickListener {
            showStep(OnboardingStep.CHOOSE_PATH)
        }

        // Choose Path
        createWalletButton.setOnClickListener {
            isRestoreFlow = false
            startNewWalletFlow()
        }

        restoreWalletButton.setOnClickListener {
            isRestoreFlow = true
            showStep(OnboardingStep.ENTER_SEED)
        }

        // Enter Seed
        seedBackButton.setOnClickListener {
            showStep(OnboardingStep.CHOOSE_PATH)
        }

        pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        seedContinueButton.setOnClickListener {
            startRestoreFlow()
        }

        // Review Mints
        mintsBackButton.setOnClickListener {
            if (isRestoreFlow) {
                showStep(OnboardingStep.ENTER_SEED)
            } else {
                showStep(OnboardingStep.CHOOSE_PATH)
            }
        }

        mintsContinueButton.setOnClickListener {
            if (isRestoreFlow) {
                performRestore()
            } else {
                completeNewWalletSetup()
            }
        }

        // Success
        enterWalletButton.setOnClickListener {
            completeOnboarding()
        }
    }

    private fun showStep(step: OnboardingStep) {
        currentStep = step

        // Update window bars based on the step (green only for welcome screen)
        updateWindowBarsForStep(step)

        // Hide all containers
        welcomeContainer.visibility = View.GONE
        choosePathContainer.visibility = View.GONE
        enterSeedContainer.visibility = View.GONE
        generatingContainer.visibility = View.GONE
        fetchingContainer.visibility = View.GONE
        reviewMintsContainer.visibility = View.GONE
        restoringContainer.visibility = View.GONE
        successContainer.visibility = View.GONE

        // Show appropriate container with animation
        val containerToShow = when (step) {
            OnboardingStep.WELCOME -> welcomeContainer
            OnboardingStep.CHOOSE_PATH -> choosePathContainer
            OnboardingStep.ENTER_SEED -> enterSeedContainer
            OnboardingStep.GENERATING_WALLET -> generatingContainer
            OnboardingStep.FETCHING_BACKUP -> fetchingContainer
            OnboardingStep.REVIEW_MINTS -> reviewMintsContainer
            OnboardingStep.RESTORING -> restoringContainer
            OnboardingStep.SUCCESS -> successContainer
        }

        containerToShow.visibility = View.VISIBLE
        
        // Use special animation for welcome screen
        if (step == OnboardingStep.WELCOME) {
            animateWelcomeScreen()
        } else {
        animateContainerIn(containerToShow)
        }
    }

    private fun animateContainerIn(container: View) {
        container.alpha = 0f
        container.translationY = 30f

        ObjectAnimator.ofFloat(container, "alpha", 0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }.start()

        ObjectAnimator.ofFloat(container, "translationY", 30f, 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
        }.start()
    }

    /**
     * Elegant Apple-like animation for the welcome screen.
     * Two-tone design: Navy curved section at top, fluorescent green at bottom.
     * 1. Navy curve slides/fades in from top
     * 2. Wordmark fades in with scale animation
     * 3. Tagline fades in with slide up
     * 4. Button fades in
     */
    private fun animateWelcomeScreen() {
        // Reset all views to initial state
        welcomeNavyCurve.alpha = 0f
        welcomeNavyCurve.translationY = -50f
        welcomeWordmark.alpha = 0f
        welcomeWordmark.scaleX = 0.95f
        welcomeWordmark.scaleY = 0.95f
        welcomeTagline.alpha = 0f
        welcomeTagline.translationY = 15f
        acceptButton.alpha = 0f
        acceptButton.translationY = 20f

        // 1. Animate navy curve sliding down and fading in
        val navyCurveAlpha = ObjectAnimator.ofFloat(welcomeNavyCurve, "alpha", 0f, 1f).apply {
            duration = 700
            startDelay = 150
            interpolator = DecelerateInterpolator()
        }
        val navyCurveTranslate = ObjectAnimator.ofFloat(welcomeNavyCurve, "translationY", -50f, 0f).apply {
            duration = 700
            startDelay = 150
            interpolator = DecelerateInterpolator()
        }

        // 2. Animate wordmark fade in with subtle scale - appears immediately (no delay)
        val wordmarkAlpha = ObjectAnimator.ofFloat(welcomeWordmark, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 0
            interpolator = DecelerateInterpolator()
        }
        val wordmarkScaleX = ObjectAnimator.ofFloat(welcomeWordmark, "scaleX", 0.95f, 1f).apply {
            duration = 400
            startDelay = 0
            interpolator = DecelerateInterpolator()
        }
        val wordmarkScaleY = ObjectAnimator.ofFloat(welcomeWordmark, "scaleY", 0.95f, 1f).apply {
            duration = 400
            startDelay = 0
            interpolator = DecelerateInterpolator()
        }

        // 3. Animate tagline fade in with slide up
        val taglineAlpha = ObjectAnimator.ofFloat(welcomeTagline, "alpha", 0f, 1f).apply {
            duration = 450
            startDelay = 850
            interpolator = DecelerateInterpolator()
        }
        val taglineTranslate = ObjectAnimator.ofFloat(welcomeTagline, "translationY", 15f, 0f).apply {
            duration = 450
            startDelay = 850
            interpolator = DecelerateInterpolator()
        }

        // 4. Animate button fade in
        val buttonAlpha = ObjectAnimator.ofFloat(acceptButton, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 1150
            interpolator = DecelerateInterpolator()
        }
        val buttonTranslate = ObjectAnimator.ofFloat(acceptButton, "translationY", 20f, 0f).apply {
            duration = 400
            startDelay = 1150
            interpolator = DecelerateInterpolator()
        }

        // Play all animations
        AnimatorSet().apply {
            playTogether(
                navyCurveAlpha, navyCurveTranslate,
                wordmarkAlpha, wordmarkScaleX, wordmarkScaleY,
                taglineAlpha, taglineTranslate,
                buttonAlpha, buttonTranslate
            )
            start()
        }
    }

    // === New Wallet Flow ===

    private fun startNewWalletFlow() {
        showStep(OnboardingStep.GENERATING_WALLET)
        generatingStatus.text = getString(R.string.onboarding_status_creating_wallet)

        lifecycleScope.launch {
            try {
                // Simulate a brief delay for UX
                delay(800)

                withContext(Dispatchers.Main) {
                    generatingStatus.text = getString(R.string.onboarding_status_generating_seed)
                }

                delay(600)

                // Generate new mnemonic
                val mnemonic = withContext(Dispatchers.IO) {
                    generateMnemonic()
                }
                generatedMnemonic = mnemonic

                withContext(Dispatchers.Main) {
                    generatingStatus.text = getString(R.string.onboarding_status_setting_up_mints)
                }

                delay(500)

                // Get default mints from MintManager
                val mintManager = MintManager.getInstance(this@OnboardingActivity)
                
                // Clear and reset to defaults to ensure clean state
                mintManager.resetToDefaults()
                
                val defaultMints = mintManager.getAllowedMints()

                discoveredMints.clear()
                selectedMints.clear()
                discoveredMints.addAll(defaultMints)
                selectedMints.addAll(defaultMints)
                backupFound = false

                withContext(Dispatchers.Main) {
                    showStep(OnboardingStep.REVIEW_MINTS)
                    updateReviewMintsUI()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        getString(R.string.onboarding_error_creating_wallet, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                    showStep(OnboardingStep.CHOOSE_PATH)
                }
            }
        }
    }

    private fun completeNewWalletSetup() {
        showStep(OnboardingStep.GENERATING_WALLET)
        generatingStatus.text = getString(R.string.onboarding_status_initializing_wallet)

        lifecycleScope.launch {
            try {
                val mnemonic = generatedMnemonic ?: throw IllegalStateException("No mnemonic generated")

                // Initialize CashuWalletManager with the generated mnemonic
                val prefs = getSharedPreferences("CashuWalletPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("wallet_mnemonic", mnemonic).apply()

                withContext(Dispatchers.Main) {
                    generatingStatus.text = getString(R.string.onboarding_status_connecting_mints)
                }

                delay(500)

                // Initialize the wallet manager
                CashuWalletManager.init(this@OnboardingActivity)

                withContext(Dispatchers.Main) {
                    generatingStatus.text = getString(R.string.onboarding_status_fetching_mints)
                }

                // Fetch mint info for selected mints
                val mintManager = MintManager.getInstance(this@OnboardingActivity)
                for (mintUrl in selectedMints) {
                    try {
                        val info = CashuWalletManager.fetchMintInfo(mintUrl)
                        if (info != null) {
                            val infoJson = CashuWalletManager.mintInfoToJson(info)
                            mintManager.setMintInfo(mintUrl, infoJson)
                            mintManager.setMintRefreshTimestamp(mintUrl)
                        }
                    } catch (e: Exception) {
                        // Continue even if mint info fetch fails
                    }
                }

                delay(300)

                withContext(Dispatchers.Main) {
                    showSuccessScreen(isRestore = false)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        getString(R.string.onboarding_error_initializing_wallet, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                    showStep(OnboardingStep.REVIEW_MINTS)
                }
            }
        }
    }

    // === Restore Flow ===

    private fun validateSeedInputs(): Boolean {
        val words = seedInputs.map { it.text.toString().trim().lowercase() }
        val filledCount = words.count { it.isNotBlank() }
        val allFilled = filledCount == 12
        val allValid = words.all { it.isBlank() || it.matches(Regex("^[a-z]+$")) }

        when {
            filledCount == 0 -> {
                seedValidationStatus.visibility = View.GONE
            }
            !allValid -> {
                seedValidationStatus.visibility = View.VISIBLE
                seedValidationIcon.setImageResource(R.drawable.ic_close)
                seedValidationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning_red))
                seedValidationText.text = getString(R.string.onboarding_seed_invalid_characters)
                seedValidationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning_red))
            }
            !allFilled -> {
                seedValidationStatus.visibility = View.VISIBLE
                seedValidationIcon.setImageResource(R.drawable.ic_warning)
                seedValidationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning))
                seedValidationText.text = getString(R.string.onboarding_seed_words_entered_count, filledCount)
                seedValidationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning))
            }
            else -> {
                seedValidationStatus.visibility = View.VISIBLE
                seedValidationIcon.setImageResource(R.drawable.ic_check)
                seedValidationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))
                seedValidationText.text = getString(R.string.onboarding_seed_validation_ready)
                seedValidationText.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))
            }
        }

        val canContinue = allFilled && allValid
        seedContinueButton.isEnabled = canContinue
        seedContinueButton.alpha = if (canContinue) 1f else 0.5f

        return canContinue
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(this, R.string.onboarding_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val pastedText = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
        val words = pastedText.split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (words.size != 12) {
            Toast.makeText(this, R.string.onboarding_seed_paste_invalid, Toast.LENGTH_LONG).show()
            return
        }

        words.forEachIndexed { index, word ->
            if (index < seedInputs.size) {
                seedInputs[index].setText(word.lowercase())
            }
        }

        validateSeedInputs()
        Toast.makeText(this, R.string.onboarding_seed_paste_success, Toast.LENGTH_SHORT).show()
    }

    private fun getMnemonic(): String {
        return seedInputs.map { it.text.toString().trim().lowercase() }.joinToString(" ")
    }

    private fun startRestoreFlow() {
        if (!validateSeedInputs()) return

        enteredMnemonic = getMnemonic()

        showStep(OnboardingStep.FETCHING_BACKUP)
        fetchingStatus.text = getString(R.string.onboarding_fetching_searching_backup)

        lifecycleScope.launch {
            val mnemonic = enteredMnemonic ?: return@launch

            val result = withContext(Dispatchers.IO) {
                fetchMintBackupSuspend(mnemonic)
            }

            // Get default mints
            val mintManager = MintManager.getInstance(this@OnboardingActivity)
            
            // Reset to defaults first
            mintManager.resetToDefaults()
            val defaultMints = mintManager.getAllowedMints()

            discoveredMints.clear()
            selectedMints.clear()

            if (result.success && result.mints.isNotEmpty()) {
                backupFound = true
                backupTimestamp = result.timestamp
                discoveredMints.addAll(result.mints)
                selectedMints.addAll(result.mints)

                // Add discovered mints to MintManager
                for (mintUrl in result.mints) {
                    mintManager.addMint(mintUrl)
                }
            } else {
                backupFound = false
                backupTimestamp = null
                discoveredMints.addAll(defaultMints)
                selectedMints.addAll(defaultMints)
            }

            withContext(Dispatchers.Main) {
                showStep(OnboardingStep.REVIEW_MINTS)
                updateReviewMintsUI()
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

    private fun performRestore() {
        val mnemonic = enteredMnemonic ?: return

        showStep(OnboardingStep.RESTORING)
        restoringStatus.text = getString(R.string.onboarding_restoring_initializing)
        mintProgressContainer.removeAllViews()
        mintProgressViews.clear()

        // Update MintManager with selected mints
        val mintManager = MintManager.getInstance(this)
        for (mintUrl in selectedMints) {
            if (!mintManager.isMintAllowed(mintUrl)) {
                mintManager.addMint(mintUrl)
            }
        }

        // Create progress views for each mint
        for (mintUrl in selectedMints) {
            val progressView = createMintProgressView(mintUrl)
            mintProgressContainer.addView(progressView)
            mintProgressViews[mintUrl] = progressView
        }

        lifecycleScope.launch {
            try {
                val results = CashuWalletManager.restoreFromMnemonic(mnemonic) { mintUrl, status, before, after ->
                    if (selectedMints.contains(mintUrl)) {
                        withContext(Dispatchers.Main) {
                            updateMintProgress(mintUrl, status, before, after)
                        }
                    }
                }

                balanceChanges.clear()
                balanceChanges.putAll(results.filterKeys { selectedMints.contains(it) })

                withContext(Dispatchers.Main) {
                    showSuccessScreen(isRestore = true)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        getString(R.string.restore_error_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                    showStep(OnboardingStep.REVIEW_MINTS)
                }
            }
        }
    }

    // === Review Mints UI ===

    private fun updateReviewMintsUI() {
        // Update backup status card
        if (isRestoreFlow) {
            backupStatusCard.visibility = View.VISIBLE
            if (backupFound) {
                backupStatusCard.background = ContextCompat.getDrawable(this, R.drawable.bg_success_card)
                backupStatusIcon.setImageResource(R.drawable.ic_cloud_done)
                backupStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))
                backupStatusTitle.text = getString(R.string.onboarding_backup_found_title)
                backupStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))

                val dateStr = backupTimestamp?.let {
                    SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(it * 1000))
                } ?: getString(R.string.restore_backup_unknown_date)
                backupStatusSubtitle.text = getString(R.string.onboarding_backup_found_subtitle, dateStr)
            } else {
                backupStatusCard.background = ContextCompat.getDrawable(this, R.drawable.bg_info_card)
                backupStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                backupStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_text_tertiary))
                backupStatusTitle.text = getString(R.string.onboarding_backup_not_found_title)
                backupStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
                backupStatusSubtitle.text = getString(R.string.onboarding_backup_not_found_subtitle)
            }
            mintsSubtitle.text = getString(R.string.onboarding_mints_subtitle_restore)
            mintsContinueButton.text = getString(R.string.onboarding_mints_continue_restore)
        } else {
            backupStatusCard.visibility = View.GONE
            mintsSubtitle.text = getString(R.string.onboarding_mints_subtitle_description)
            mintsContinueButton.text = getString(R.string.onboarding_mints_continue_new_wallet)
        }

        // Update mints list
        mintsListContainer.removeAllViews()

        val sortedMints = discoveredMints.sortedBy { extractMintName(it).lowercase() }
        for (mintUrl in sortedMints) {
            val mintView = createMintSelectionView(mintUrl, selectedMints.contains(mintUrl))
            mintsListContainer.addView(mintView)
        }

        updateMintsCount()
    }

    private fun createMintSelectionView(mintUrl: String, isSelected: Boolean): View {
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

        val checkbox = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                marginEnd = 14.dpToPx()
            }
            updateCheckboxState(this, isSelected)
        }

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
        val pluralSuffix = if (count != 1) "s" else ""
        mintsCountText.text = getString(R.string.onboarding_mints_count, count, pluralSuffix)

        mintsContinueButton.isEnabled = count > 0
        mintsContinueButton.alpha = if (count > 0) 1f else 0.5f
    }

    // === Restore Progress ===

    private fun createMintProgressView(mintUrl: String): View {
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

        // Status container (spinner or icon)
        val statusFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                marginEnd = 14.dpToPx()
            }
        }

        val spinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isIndeterminate = true
            indeterminateTintList = ContextCompat.getColorStateList(context, R.color.color_text_tertiary)
        }

        val statusIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        statusFrame.addView(spinner)
        statusFrame.addView(statusIcon)

        // Info container
        val infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(this).apply {
            text = mintManager.getMintDisplayName(mintUrl)
            setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            tag = "name"
        }

        val statusText = TextView(this).apply {
            text = getString(R.string.restore_progress_status_waiting)
            setTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            textSize = 13f
            tag = "status"
        }

        infoContainer.addView(nameText)
        infoContainer.addView(statusText)

        // Balance change text
        val balanceText = TextView(this).apply {
            visibility = View.GONE
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            tag = "balance"
        }

        container.addView(statusFrame)
        container.addView(infoContainer)
        container.addView(balanceText)

        // Store references
        container.tag = mapOf(
            "spinner" to spinner,
            "statusIcon" to statusIcon,
            "statusText" to statusText,
            "balanceText" to balanceText
        )

        return container
    }

    private fun updateMintProgress(mintUrl: String, status: String, before: Long, after: Long) {
        val view = mintProgressViews[mintUrl] ?: return
        val tags = view.tag as? Map<*, *> ?: return

        val spinner = tags["spinner"] as? ProgressBar
        val statusIcon = tags["statusIcon"] as? ImageView
        val statusText = tags["statusText"] as? TextView
        val balanceText = tags["balanceText"] as? TextView

        statusText?.text = status

        when {
            status == "Complete" -> {
                spinner?.visibility = View.GONE
                statusIcon?.visibility = View.VISIBLE
                statusIcon?.setImageResource(R.drawable.ic_check)
                statusIcon?.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))

                val diff = after - before
                if (diff != 0L) {
                    balanceText?.visibility = View.VISIBLE
                    val diffText = if (diff >= 0) getString(R.string.restore_progress_balance_increase, diff)
                                   else getString(R.string.restore_progress_balance_decrease, diff)
                    balanceText?.text = diffText
                    balanceText?.setTextColor(
                        ContextCompat.getColor(
                            this,
                            if (diff >= 0) R.color.color_success_green else R.color.color_warning_red
                        )
                    )
                }
            }
            status.startsWith("Failed") -> {
                spinner?.visibility = View.GONE
                statusIcon?.visibility = View.VISIBLE
                statusIcon?.setImageResource(R.drawable.ic_close)
                statusIcon?.setColorFilter(ContextCompat.getColor(this, R.color.color_warning_red))
            }
            else -> {
                spinner?.visibility = View.VISIBLE
                statusIcon?.visibility = View.GONE
            }
        }
    }

    // === Success Screen ===

    private fun showSuccessScreen(isRestore: Boolean) {
        showStep(OnboardingStep.SUCCESS)

        if (isRestore) {
            val totalRecovered = balanceChanges.values.sumOf { maxOf(0L, it.second - it.first) }
            val totalBalance = balanceChanges.values.sumOf { it.second }

            successTitle.text = getString(R.string.onboarding_success_restored_title)
            successSubtitle.text = if (totalRecovered > 0) {
                getString(R.string.onboarding_success_restored_recovered, totalRecovered)
            } else {
                getString(R.string.onboarding_success_restored_total_balance, totalBalance)
            }

            // Show balance changes
            if (balanceChanges.isNotEmpty()) {
                successBalanceSection.visibility = View.VISIBLE
                balanceChangesContainer.removeAllViews()

                for ((mintUrl, balances) in balanceChanges) {
                    val (before, after) = balances
                    val diff = after - before

                    val itemView = createBalanceChangeItem(mintUrl, before, after, diff)
                    balanceChangesContainer.addView(itemView)
                }
            } else {
                successBalanceSection.visibility = View.GONE
            }
        } else {
            successTitle.text = getString(R.string.onboarding_success_created_title)
            successSubtitle.text = getString(R.string.onboarding_success_created_subtitle)
            successBalanceSection.visibility = View.GONE
        }

        // Animate checkmark
        val checkmark = successContainer.findViewById<ImageView>(R.id.success_checkmark)
        checkmark?.let { animateCheckmark(it) }
    }

    private fun createBalanceChangeItem(mintUrl: String, before: Long, after: Long, diff: Long): View {
        val mintManager = MintManager.getInstance(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
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
            text = getString(R.string.restore_success_balance_line, after)
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
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        container.addView(infoContainer)
        container.addView(diffText)

        return container
    }

    private fun animateCheckmark(checkmark: View) {
        checkmark.scaleX = 0f
        checkmark.scaleY = 0f
        checkmark.alpha = 0f

        val scaleX = ObjectAnimator.ofFloat(checkmark, "scaleX", 0f, 1.2f, 1f)
        val scaleY = ObjectAnimator.ofFloat(checkmark, "scaleY", 0f, 1.2f, 1f)
        val alpha = ObjectAnimator.ofFloat(checkmark, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 200
            start()
        }
    }

    private fun completeOnboarding() {
        setOnboardingComplete(this, true)

        // Initialize CashuWalletManager if not already done
        CashuWalletManager.init(this)

        // Go to main activity
        val intent = Intent(this, ModernPOSActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentStep) {
            OnboardingStep.WELCOME -> finish()
            OnboardingStep.CHOOSE_PATH -> showStep(OnboardingStep.WELCOME)
            OnboardingStep.ENTER_SEED -> showStep(OnboardingStep.CHOOSE_PATH)
            OnboardingStep.REVIEW_MINTS -> {
                if (isRestoreFlow) {
                    showStep(OnboardingStep.ENTER_SEED)
                } else {
                    showStep(OnboardingStep.CHOOSE_PATH)
                }
            }
            // Don't allow back during loading states
            OnboardingStep.GENERATING_WALLET,
            OnboardingStep.FETCHING_BACKUP,
            OnboardingStep.RESTORING,
            OnboardingStep.SUCCESS -> {
                // No back action
            }
        }
    }
}
