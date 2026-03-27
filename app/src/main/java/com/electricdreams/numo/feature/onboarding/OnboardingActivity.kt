package com.electricdreams.numo.feature.onboarding

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.viewpager2.widget.ViewPager2
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.ModernPOSActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintIconCache
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.util.MintProfileService
import com.electricdreams.numo.feature.scanner.QRScannerActivity
import com.electricdreams.numo.nostr.NostrMintBackup
import com.electricdreams.numo.ui.seed.SeedWordEditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.cashudevkit.generateMnemonic
import org.json.JSONObject
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
        private val ONBOARDING_DEFAULT_MINTS = listOf(
            "https://mint.minibits.cash/Bitcoin",
            "https://mint.chorus.community",
            "https://mint.cubabitcoin.org",
            "https://mint.coinos.io"
        )

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
    private var welcomeAnimator: OnboardingWelcomeAnimator? = null

    // === Data ===
    private var generatedMnemonic: String? = null
    private var enteredMnemonic: String? = null
    private val discoveredMints = linkedSetOf<String>()
    private val selectedMints = linkedSetOf<String>()
    private val onboardingMintDisplayNames = mutableMapOf<String, String>()
    private var backupFound = false
    private var backupTimestamp: Long? = null
    private val balanceChanges = mutableMapOf<String, Pair<Long, Long>>()
    private lateinit var mintProfileService: MintProfileService

    // === Views ===
    // Step 1: Welcome
    private lateinit var welcomeContainer: FrameLayout
    private lateinit var welcomeBackgroundOverlay: View
    private lateinit var welcomeNavyCurve: ImageView
    private lateinit var welcomeWordmark: ImageView
    private lateinit var welcomeTagline: TextView
    private lateinit var termsText: TextView
    private lateinit var acceptButton: MaterialButton
    private lateinit var emojiBurstContainer: FrameLayout

    // Step 2: Choose Path
    private lateinit var choosePathContainer: FrameLayout
    private lateinit var createWalletButton: View
    private lateinit var restoreWalletButton: View

    // Teaser + Explainer
    private lateinit var teaserCard: View
    private lateinit var explainerOverlay: View
    private lateinit var explainerSheet: View
    private lateinit var explainerBackdrop: View
    private lateinit var explainerViewPager: ViewPager2
    private lateinit var explainerCloseBtn: View
    private lateinit var chevronHint1: ImageView
    private lateinit var chevronHint2: ImageView
    private lateinit var chevronHint3: ImageView
    private lateinit var chevronHintContainer: View
    private var explainerOpen = false
    private var chevronAnimatorSet: AnimatorSet? = null

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
    private lateinit var mintsRecyclerView: RecyclerView
    private lateinit var mintAdapter: OnboardingMintAdapter
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
    private val seedInputs = mutableListOf<SeedWordEditText>()
    private val mintProgressViews = mutableMapOf<String, View>()

    private var addMintBottomSheet: AddMintBottomSheet? = null

    private val onboardingQrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrValue = result.data?.getStringExtra(QRScannerActivity.EXTRA_QR_VALUE)
            qrValue?.let { scannedUrl ->
                // Dismiss the bottom sheet if open and add the scanned mint
                addMintBottomSheet?.dismiss()
                addMintBottomSheet = null
                addDifferentMint(mintProfileService.normalizeUrl(scannedUrl))
            }
        }
    }

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
        mintProfileService = MintProfileService.getInstance(this)
        MintIconCache.initialize(this)

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
            // Start with white bars (animator will transition to navy)
            val whiteColor = android.graphics.Color.WHITE
            window.statusBarColor = whiteColor
            window.navigationBarColor = whiteColor

            // Dark icons on white background
            windowInsetsController.isAppearanceLightStatusBars = true
            windowInsetsController.isAppearanceLightNavigationBars = true
        } else if (step == OnboardingStep.CHOOSE_PATH) {
            // Navy bars — nav bar matches dark teaser area at bottom
            window.statusBarColor = android.graphics.Color.parseColor("#0A2540")
            window.navigationBarColor = android.graphics.Color.parseColor("#0A2540")
            windowInsetsController.isAppearanceLightStatusBars = false
            windowInsetsController.isAppearanceLightNavigationBars = false
        } else {
            // Navy bars for all other onboarding screens
            val navyLight = android.graphics.Color.parseColor("#0A2540")
            window.statusBarColor = navyLight
            window.navigationBarColor = navyLight
            windowInsetsController.isAppearanceLightStatusBars = false
            windowInsetsController.isAppearanceLightNavigationBars = false
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
        emojiBurstContainer = findViewById(R.id.emoji_burst_container)

        // Choose Path
        choosePathContainer = findViewById(R.id.choose_path_container)
        createWalletButton = findViewById(R.id.create_wallet_button)
        restoreWalletButton = findViewById(R.id.restore_wallet_button)

        // Teaser + Explainer
        teaserCard = findViewById(R.id.teaser_card)
        explainerOverlay = findViewById(R.id.explainer_overlay)
        explainerSheet = findViewById(R.id.explainer_sheet)
        explainerBackdrop = findViewById(R.id.explainer_backdrop)
        explainerViewPager = findViewById(R.id.explainer_view_pager)
        explainerCloseBtn = findViewById(R.id.explainer_close_btn)
        chevronHint1 = findViewById(R.id.chevron_hint_1)
        chevronHint2 = findViewById(R.id.chevron_hint_2)
        chevronHint3 = findViewById(R.id.chevron_hint_3)
        chevronHintContainer = findViewById(R.id.chevron_hint_container)

        setupExplainerViewPager()

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
        mintsRecyclerView = findViewById(R.id.mints_recycler_view)
        mintsContinueButton = findViewById(R.id.mints_continue_button)
        mintsBackButton = findViewById(R.id.mints_back_button)

        // Setup RecyclerView + Adapter
        mintAdapter = OnboardingMintAdapter(object : OnboardingMintAdapter.Listener {
            override fun onLoadMintIcon(mintUrl: String, iconView: ShapeableImageView) {
                loadMintIcon(mintUrl, iconView)
            }
            override fun onResolveMintName(mintUrl: String): String {
                return resolveOnboardingMintDisplayName(mintUrl)
            }
            override fun onMintAcceptedChanged() {
                updateContinueButtonState()
            }
            override fun onDefaultMintChanged(newDefaultUrl: String) {
                updateContinueButtonState()
            }
            override fun onAddMintClicked() {
                showAddMintBottomSheet()
            }
            override fun onRequestSetDefault(mintUrl: String, mintName: String) {
                showSetDefaultConfirmationDialog(mintUrl, mintName)
            }
        })
        mintAdapter.setHeaderStrings(
            acceptFromTitle = getString(R.string.onboarding_mints_accept_from_header)
        )
        mintsRecyclerView.layoutManager = LinearLayoutManager(this)
        mintsRecyclerView.adapter = mintAdapter

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
                    // No super — avoids default blue color and underline
                    ds.isFakeBoldText = true
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
            background = ContextCompat.getDrawable(context, R.drawable.bg_seed_input_dark)
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }

        val indexText = TextView(this).apply {
            text = "$index"
            setTextColor(android.graphics.Color.parseColor("#73FFFFFF"))
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER
        }

        val input = SeedWordEditText(this).apply {
            hint = ""
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#4DFFFFFF"))
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
                    if (hasFocus) R.drawable.bg_seed_input_dark_focused else R.drawable.bg_seed_input_dark
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

        // Teaser + Explainer
        setupTeaserTouchListener()
        explainerCloseBtn.setOnClickListener { closeExplainer() }

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

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun showStep(step: OnboardingStep) {
        // Always dismiss keyboard when changing steps
        hideKeyboard()

        // Stop welcome animation if leaving welcome screen
        if (currentStep == OnboardingStep.WELCOME && step != OnboardingStep.WELCOME) {
            welcomeAnimator?.stop()
            welcomeAnimator = null
        }

        // Close explainer and stop chevron animation if leaving CHOOSE_PATH
        if (currentStep == OnboardingStep.CHOOSE_PATH && step != OnboardingStep.CHOOSE_PATH) {
            resetExplainer()
            stopChevronPulseAnimation()
        }

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

        // Start chevron animation when showing CHOOSE_PATH
        if (step == OnboardingStep.CHOOSE_PATH) {
            startChevronPulseAnimation()
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

    // ══════════════════════════════════════════
    // Explainer overlay
    // ══════════════════════════════════════════

    private fun setupExplainerViewPager() {
        explainerViewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        explainerViewPager.adapter = ExplainerSlideAdapter()
        explainerViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Show chevrons on slides 0 and 1, hide on last slide
                chevronHintContainer.animate()
                    .alpha(if (position <= 1) 1f else 0f)
                    .setDuration(200)
                    .start()
            }
        })

        // Tap chevrons to advance to next slide
        chevronHintContainer.setOnClickListener {
            val next = explainerViewPager.currentItem + 1
            if (next < (explainerViewPager.adapter?.itemCount ?: 0)) {
                explainerViewPager.setCurrentItem(next, true)
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTeaserTouchListener() {
        var startY = 0f
        var isDragging = false

        teaserCard.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diff = startY - event.rawY
                    if (diff > 10) {
                        isDragging = true
                        // Show the overlay and track finger
                        explainerOverlay.visibility = View.VISIBLE
                        val screenHeight = explainerOverlay.height.toFloat()
                        val progress = (diff / screenHeight).coerceIn(0f, 1f)
                        explainerSheet.translationY = screenHeight * (1f - progress)
                        explainerBackdrop.alpha = progress
                        // Fade out teaser proportionally
                        teaserCard.alpha = 1f - progress
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val diff = startY - event.rawY
                    if (isDragging) {
                        val threshold = 80 * resources.displayMetrics.density
                        if (diff > threshold) {
                            openExplainer()
                        } else {
                            snapExplainerClosed()
                        }
                    } else if (diff < 10 * resources.displayMetrics.density) {
                        // Tap — open
                        openExplainer()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setExplainerWindowBars(open: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val darkNavy = android.graphics.Color.parseColor("#0A2540")
        if (open) {
            window.statusBarColor = darkNavy
        } else {
            window.statusBarColor = android.graphics.Color.parseColor("#0A2540")
        }
        window.navigationBarColor = darkNavy // always dark navy at bottom
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    private fun openExplainer() {
        explainerOpen = true
        explainerOverlay.visibility = View.VISIBLE
        setExplainerWindowBars(true)
        val screenHeight = explainerOverlay.height.toFloat()

        // Animate sheet up
        ObjectAnimator.ofFloat(explainerSheet, "translationY", explainerSheet.translationY, 0f).apply {
            duration = 450
            interpolator = android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)
        }.start()

        // Fade in backdrop
        ObjectAnimator.ofFloat(explainerBackdrop, "alpha", explainerBackdrop.alpha, 1f).apply {
            duration = 350
        }.start()

        // Slide teaser down and fade out
        ObjectAnimator.ofFloat(teaserCard, "translationY", 0f, screenHeight * 0.3f).apply {
            duration = 350
        }.start()
        ObjectAnimator.ofFloat(teaserCard, "alpha", 1f, 0f).apply {
            duration = 200
        }.start()
    }

    private fun closeExplainer() {
        explainerOpen = false
        setExplainerWindowBars(false)
        val screenHeight = explainerOverlay.height.toFloat()

        // Animate sheet down
        ObjectAnimator.ofFloat(explainerSheet, "translationY", 0f, screenHeight).apply {
            duration = 450
            interpolator = android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    explainerOverlay.visibility = View.GONE
                    explainerViewPager.setCurrentItem(0, false)
                }
            })
        }.start()

        // Fade backdrop out
        ObjectAnimator.ofFloat(explainerBackdrop, "alpha", 1f, 0f).apply {
            duration = 350
        }.start()

        // Bounce teaser back in after a short delay
        teaserCard.postDelayed({
            teaserCard.translationY = 200f
            ObjectAnimator.ofFloat(teaserCard, "translationY", 200f, 0f).apply {
                duration = 500
                interpolator = OvershootInterpolator(1.2f)
            }.start()
            ObjectAnimator.ofFloat(teaserCard, "alpha", 0f, 1f).apply {
                duration = 300
            }.start()
        }, 300)
    }

    private fun snapExplainerClosed() {
        setExplainerWindowBars(false)
        val screenHeight = explainerOverlay.height.toFloat()

        ObjectAnimator.ofFloat(explainerSheet, "translationY", explainerSheet.translationY, screenHeight).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    explainerOverlay.visibility = View.GONE
                }
            })
        }.start()

        ObjectAnimator.ofFloat(explainerBackdrop, "alpha", explainerBackdrop.alpha, 0f).apply {
            duration = 300
        }.start()

        // Restore teaser
        ObjectAnimator.ofFloat(teaserCard, "alpha", teaserCard.alpha, 1f).apply {
            duration = 200
        }.start()
    }

    private fun resetExplainer() {
        if (explainerOpen) {
            explainerOpen = false
            explainerOverlay.visibility = View.GONE
            explainerSheet.translationY = 2000f
            explainerBackdrop.alpha = 0f
            teaserCard.translationY = 0f
            teaserCard.alpha = 1f
            explainerViewPager.setCurrentItem(0, false)
        }
    }

    private fun startChevronPulseAnimation() {
        stopChevronPulseAnimation()

        val chevrons = listOf(chevronHint1, chevronHint2, chevronHint3)
        val animators = chevrons.mapIndexed { index, chevron ->
            ObjectAnimator.ofFloat(chevron, "alpha", 0.12f, 0.7f, 0.12f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = (index * 200).toLong()
            }
        }

        chevronAnimatorSet = AnimatorSet().apply {
            playTogether(animators.map { it as android.animation.Animator })
            start()
        }
    }

    private fun stopChevronPulseAnimation() {
        chevronAnimatorSet?.cancel()
        chevronAnimatorSet = null
    }

    /**
     * Cinematic welcome screen animation with 5 phases:
     * 1. Logo splash with shimmer
     * 2. Logo translates from center to top
     * 3. Tagline fades in
     * 4. Emoji tiles form circle then burst outward
     * 5. Get Started button and terms fade in
     */
    private fun animateWelcomeScreen() {
        welcomeAnimator?.stop()
        welcomeAnimator = OnboardingWelcomeAnimator(
            activity = this,
            container = welcomeContainer,
            wordmark = welcomeWordmark,
            tagline = welcomeTagline,
            acceptButton = acceptButton,
            termsText = termsText,
            emojiContainer = emojiBurstContainer
        )
        welcomeAnimator?.start(lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        welcomeAnimator?.pause()
    }

    override fun onResume() {
        super.onResume()
        welcomeAnimator?.resume()
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

                discoveredMints.clear()
                selectedMints.clear()
                onboardingMintDisplayNames.clear()
                val healthyMints = filterHealthyMints(ONBOARDING_DEFAULT_MINTS.shuffled())
                discoveredMints.addAll(healthyMints)
                selectedMints.addAll(healthyMints)
                backupFound = false

                withContext(Dispatchers.Main) {
                    showStep(OnboardingStep.REVIEW_MINTS)
                    updateReviewMintsUI()
                    refreshMintProfilesForReview()
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
                PreferenceStore.wallet(this@OnboardingActivity).putString("wallet_mnemonic", mnemonic)
                applySelectedMintsToMintManager()

                withContext(Dispatchers.Main) {
                    generatingStatus.text = getString(R.string.onboarding_status_connecting_mints)
                }

                delay(500)

                // Initialize the wallet manager
                CashuWalletManager.init(this@OnboardingActivity)

                withContext(Dispatchers.Main) {
                    generatingStatus.text = getString(R.string.onboarding_status_fetching_mints)
                }

                // Fetch mint info for selected mints concurrently
                selectedMints.map { mintUrl ->
                    async {
                        mintProfileService.fetchAndStoreMintProfile(mintUrl, validateEndpoint = false)
                    }
                }.awaitAll()

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
                seedValidationIcon.setColorFilter(ContextCompat.getColor(this, R.color.numo_fluorescent_green))
                seedValidationText.text = getString(R.string.onboarding_seed_validation_ready)
                seedValidationText.setTextColor(ContextCompat.getColor(this, R.color.numo_fluorescent_green))
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

        validateSeedInputs()
        Toast.makeText(this, getString(R.string.onboarding_seed_paste_success), Toast.LENGTH_SHORT).show()
        return true
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

            discoveredMints.clear()
            selectedMints.clear()
            onboardingMintDisplayNames.clear()

            if (result.success && result.mints.isNotEmpty()) {
                backupFound = true
                backupTimestamp = result.timestamp
                val normalizedBackupMints = result.mints
                    .map { mintProfileService.normalizeUrl(it) }
                    .filter { it.isNotBlank() }
                    .shuffled()
                discoveredMints.addAll(normalizedBackupMints)
                selectedMints.addAll(normalizedBackupMints)
            } else {
                backupFound = false
                backupTimestamp = null
                val healthyMints = filterHealthyMints(ONBOARDING_DEFAULT_MINTS.shuffled())
                discoveredMints.addAll(healthyMints)
                selectedMints.addAll(healthyMints)
            }

            withContext(Dispatchers.Main) {
                showStep(OnboardingStep.REVIEW_MINTS)
                updateReviewMintsUI()
                refreshMintProfilesForReview()
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

        // Persist final onboarding selection before restore.
        applySelectedMintsToMintManager()

        // Create progress views for each mint
        for (mintUrl in selectedMints) {
            val progressView = createMintProgressView(mintUrl)
            mintProgressContainer.addView(progressView)
            mintProgressViews[mintUrl] = progressView
        }

        lifecycleScope.launch {
            try {
                val results = CashuWalletManager.restoreFromMnemonic(mnemonic, this@OnboardingActivity) { mintUrl, status, before, after ->
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
                backupStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.numo_fluorescent_green))
                backupStatusTitle.text = getString(R.string.onboarding_backup_found_title)
                backupStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.numo_fluorescent_green))

                val dateStr = backupTimestamp?.let {
                    SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(it * 1000))
                } ?: getString(R.string.restore_backup_unknown_date)
                backupStatusSubtitle.text = getString(R.string.onboarding_backup_found_subtitle, dateStr)
            } else {
                backupStatusCard.setBackgroundColor(ContextCompat.getColor(this, R.color.numo_navy))
                backupStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                backupStatusIcon.setColorFilter(android.graphics.Color.parseColor("#73FFFFFF"))
                backupStatusTitle.text = getString(R.string.onboarding_backup_not_found_title)
                backupStatusTitle.setTextColor(android.graphics.Color.WHITE)
                backupStatusSubtitle.text = getString(R.string.onboarding_backup_not_found_subtitle)
            }
            mintsContinueButton.text = getString(R.string.onboarding_mints_continue_restore)
        } else {
            backupStatusCard.visibility = View.GONE
            mintsContinueButton.text = getString(R.string.onboarding_mints_continue_new_wallet)
        }

        // Populate the RecyclerView adapter
        // First mint is the default, rest are popular
        val mintList = discoveredMints.toList()
        val defaultMint = mintList.firstOrNull() ?: return
        val popularMints = mintList.drop(1)
        // All popular mints start as accepted
        val acceptedMints = selectedMints.filter { it != defaultMint }.toSet()

        mintAdapter.setMints(defaultMint, popularMints, acceptedMints)
        updateContinueButtonState()
    }

    private fun showAddMintBottomSheet() {
        val sheet = AddMintBottomSheet.newInstance(object : AddMintBottomSheet.Listener {
            override fun onAddMintUrl(url: String) {
                addDifferentMint(url)
            }
            override fun onScanQrCode() {
                openOnboardingMintQrScanner()
            }
        })
        addMintBottomSheet = sheet
        sheet.show(supportFragmentManager, "AddMintBottomSheet")
    }

    private fun showSetDefaultConfirmationDialog(mintUrl: String, mintName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.onboarding_mints_set_default_title))
            .setMessage(getString(R.string.onboarding_mints_set_default_message, mintName))
            .setPositiveButton(getString(R.string.onboarding_mints_set_default_confirm)) { _, _ ->
                mintAdapter.confirmSetDefault(mintUrl)
                updateContinueButtonState()
            }
            .setNegativeButton(getString(R.string.onboarding_mints_set_default_cancel), null)
            .show()
    }

    private fun updateContinueButtonState() {
        val totalSelected = mintAdapter.getAllSelectedMints().size
        mintsContinueButton.isEnabled = totalSelected > 0
        mintsContinueButton.alpha = if (totalSelected > 0) 1f else 0.35f
    }

    private fun loadMintIcon(mintUrl: String, iconView: ShapeableImageView) {
        val cachedFile = MintIconCache.getCachedIconFile(mintUrl)
        if (cachedFile != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    iconView.setImageBitmap(bitmap)
                    iconView.clearColorFilter()
                    return
                }
            } catch (_: Exception) {
                // Keep fallback icon below.
            }
        }

        iconView.setImageResource(R.drawable.ic_bitcoin)
        iconView.setColorFilter(ContextCompat.getColor(this, R.color.color_primary))
    }

    private fun resolveOnboardingMintDisplayName(mintUrl: String): String {
        val cached = onboardingMintDisplayNames[mintUrl]
        if (!cached.isNullOrBlank()) {
            return cached
        }

        val fromStoredInfo = getStoredMintName(mintUrl)
        if (!fromStoredInfo.isNullOrBlank()) {
            onboardingMintDisplayNames[mintUrl] = fromStoredInfo
            return fromStoredInfo
        }

        val generic = getString(R.string.onboarding_mint_generic_name)
        onboardingMintDisplayNames[mintUrl] = generic
        return generic
    }

    private fun getStoredMintName(mintUrl: String): String? {
        val infoJson = MintManager.getInstance(this).getMintInfo(mintUrl) ?: return null
        return try {
            val json = JSONObject(infoJson)
            json.optString("name", "").trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pings each mint in parallel and returns only the ones that respond successfully.
     * Falls back to the full list if all mints are unreachable.
     */
    private suspend fun filterHealthyMints(mints: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val results = mints.map { url ->
                async { url to mintProfileService.validateMintUrl(url).isValid }
            }.awaitAll()
            val healthy = results.filter { it.second }.map { it.first }
            healthy.ifEmpty { mints } // fall back to full list if none reachable
        }

    private fun refreshMintProfilesForReview() {
        val mintsList = discoveredMints.toList()
        for (mintUrl in mintsList) {
            lifecycleScope.launch {
                val result = mintProfileService.fetchAndStoreMintProfile(mintUrl, validateEndpoint = false)

                val displayName = result.displayName
                    ?: getStoredMintName(mintUrl)
                    ?: getString(R.string.onboarding_mint_generic_name)
                onboardingMintDisplayNames[mintUrl] = displayName

                if (currentStep == OnboardingStep.REVIEW_MINTS) {
                    // Notify adapter to refresh name display for this mint
                    mintAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun openOnboardingMintQrScanner() {
        val intent = Intent(this, QRScannerActivity::class.java).apply {
            putExtra(QRScannerActivity.EXTRA_TITLE, getString(R.string.mints_scan_mint_qr))
            putExtra(QRScannerActivity.EXTRA_INSTRUCTION, getString(R.string.mints_scan_instruction))
        }
        onboardingQrScannerLauncher.launch(intent)
    }

    private fun addDifferentMint(rawUrl: String) {
        val normalizedInput = mintProfileService.normalizeUrl(rawUrl)
        if (normalizedInput.isBlank()) {
            Toast.makeText(this, getString(R.string.mints_invalid_url), Toast.LENGTH_LONG).show()
            return
        }

        if (discoveredMints.contains(normalizedInput)) {
            Toast.makeText(this, getString(R.string.mints_already_exists), Toast.LENGTH_SHORT).show()
            return
        }

        addMintBottomSheet?.setLoading(true)

        lifecycleScope.launch {
            val validation = withContext(Dispatchers.IO) {
                mintProfileService.validateMintUrl(normalizedInput)
            }
            if (!validation.isValid || validation.normalizedUrl == null) {
                addMintBottomSheet?.setLoading(false)
                Toast.makeText(
                    this@OnboardingActivity,
                    getString(R.string.mints_invalid_url),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val mintUrl = validation.normalizedUrl
            if (discoveredMints.contains(mintUrl)) {
                addMintBottomSheet?.setLoading(false)
                Toast.makeText(
                    this@OnboardingActivity,
                    getString(R.string.mints_already_exists),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val profileResult = withContext(Dispatchers.IO) {
                mintProfileService.fetchAndStoreMintProfile(mintUrl, validateEndpoint = false)
            }

            val displayName = profileResult.displayName
                ?: getStoredMintName(mintUrl)
                ?: getString(R.string.onboarding_mint_generic_name)
            onboardingMintDisplayNames[mintUrl] = displayName

            discoveredMints.add(mintUrl)
            selectedMints.add(mintUrl)

            // Add as default — user manually added this mint, so they likely want it as default
            mintAdapter.addMintAsDefault(mintUrl)
            updateContinueButtonState()

            addMintBottomSheet?.dismiss()
            addMintBottomSheet = null
            Toast.makeText(
                this@OnboardingActivity,
                getString(R.string.mints_added_toast),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applySelectedMintsToMintManager() {
        val mintManager = MintManager.getInstance(this)

        // Get selected mints from the adapter (default + accepted popular)
        val allSelected = mintAdapter.getAllSelectedMints()
        val normalizedSelected = allSelected
            .map { mintProfileService.normalizeUrl(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val existingMints = mintManager.getAllowedMints()
        for (mintUrl in existingMints) {
            if (!normalizedSelected.contains(mintUrl)) {
                mintManager.removeMint(mintUrl)
            }
        }

        for (mintUrl in normalizedSelected) {
            mintManager.addMint(mintUrl)
        }

        // The default mint (index 0 in adapter) becomes the preferred Lightning mint
        val preferredMint = mintAdapter.getDefaultMintUrl().let { mintProfileService.normalizeUrl(it) }
        if (preferredMint.isNotBlank()) {
            mintManager.setPreferredLightningMint(preferredMint)
        }
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
            indeterminateTintList = ContextCompat.getColorStateList(context, R.color.numo_fluorescent_green)
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
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            tag = "name"
        }

        val statusText = TextView(this).apply {
            text = getString(R.string.restore_progress_status_waiting)
            setTextColor(android.graphics.Color.parseColor("#73FFFFFF"))
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
                statusIcon?.setColorFilter(ContextCompat.getColor(this, R.color.numo_fluorescent_green))

                val diff = after - before
                if (diff != 0L) {
                    balanceText?.visibility = View.VISIBLE
                    val diffText = if (diff >= 0) getString(R.string.restore_progress_balance_increase, diff)
                                   else getString(R.string.restore_progress_balance_decrease, diff)
                    balanceText?.text = diffText
                    balanceText?.setTextColor(
                        ContextCompat.getColor(
                            this,
                            if (diff >= 0) R.color.numo_fluorescent_green else R.color.color_warning_red
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
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }

        val detailText = TextView(this).apply {
            text = getString(R.string.restore_success_balance_line, after)
            setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
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
                        diff > 0 -> R.color.numo_fluorescent_green
                        diff < 0 -> R.color.color_warning_red
                        else -> R.color.numo_fluorescent_green
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
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        welcomeAnimator?.stop()
        welcomeAnimator = null
        super.onDestroy()
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
