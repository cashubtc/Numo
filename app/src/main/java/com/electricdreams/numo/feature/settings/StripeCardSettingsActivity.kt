package com.electricdreams.numo.feature.settings

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.electricdreams.numo.R
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.databinding.ActivityStripeCardSettingsBinding
import com.electricdreams.numo.databinding.ItemCardAttentionBinding
import com.electricdreams.numo.payment.card.AndroidCardSetupEnvironment
import com.electricdreams.numo.payment.card.CardSetupChecker
import com.electricdreams.numo.payment.card.CardSetupChecker.Fix
import com.electricdreams.numo.payment.card.StripeCardConfig
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.payment.card.StripeCardSettings.Snapshot
import com.electricdreams.numo.payment.card.StripeLocationsClient
import com.electricdreams.numo.payment.card.StripeTerminalManager
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Status of card payments through the merchant's own Stripe account.
 *
 * Before setup it explains what card payments need and starts [StripeCardSetupActivity].
 * Afterwards it says whether this phone is ready, and re-runs the quick checks each time it is
 * opened so a switched-off NFC chip or a revoked key shows up here rather than at checkout.
 * Card payments only turn on through a passing check in [StripeCardCheckActivity].
 */
class StripeCardSettingsActivity : AppCompatActivity() {

    private enum class State { UNSUPPORTED, NOT_SET_UP, READY, TEST_READY, PAUSED, UNCHECKED, ATTENTION, CHECK_ERROR }

    private lateinit var binding: ActivityStripeCardSettingsBinding

    /** Replaceable so tests can store a key without the Android Keystore. */
    internal lateinit var settings: StripeCardSettings

    /** Replaceable so tests can decide what the phone and Stripe report. */
    internal var checkerFactory: (StripeCardConfig) -> CardSetupChecker = { config ->
        CardSetupChecker(AndroidCardSetupEnvironment(this, readConfig = { config }))
    }

    /** Replaceable so tests can stand in for Stripe. */
    internal var locationsClient = StripeLocationsClient()

    private var isUpdatingSwitch = false
    private var checkJob: Job? = null
    private var locationNameJob: Job? = null

    /** Replaceable so tests can decide whether the device has internet. */
    internal var isOnline: () -> Boolean = { ConnectivityMonitor.getInstance(this).isOnlineNow() }

    // Leaving for the app's settings page pauses this screen, and resuming refreshes it.
    private val locationFix = LocationPermissionFix(this, onAnswered = { refresh() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStripeCardSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        settings = StripeCardSettings.getInstance(this)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        dotsAnimator?.cancel()
        dotsAnimator = null
    }

    /**
     * A slow pulse travelling from Stripe to Numo on the connect screen. Skipped when the
     * system's animations are switched off.
     */
    private fun animateConnectDots(run: Boolean) {
        if (!run || !ValueAnimator.areAnimatorsEnabled()) {
            dotsAnimator?.cancel()
            dotsAnimator = null
            return
        }
        if (dotsAnimator != null) return
        val dots = binding.introDots.children.toList()
        dotsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOTS_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                dots.forEachIndexed { index, dot ->
                    val phase = (progress - index.toFloat() / dots.size + 1f) % 1f
                    val wave = 0.5f - 0.5f * cos(2.0 * PI * phase).toFloat()
                    dot.alpha = DOT_MIN_ALPHA + (1f - DOT_MIN_ALPHA) * wave
                }
            }
            start()
        }
    }

    private var dotsAnimator: ValueAnimator? = null

    private fun setupListeners() {
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.getStartedButton.setOnClickListener {
            startActivity(StripeCardSetupActivity.setupIntent(this))
        }
        binding.checkRow.setOnClickListener { openCheck(enableOnPass = false) }
        binding.keyRow.setOnClickListener {
            startActivity(StripeCardSetupActivity.editKeyIntent(this, thenCheck = true))
        }
        binding.locationRow.setOnClickListener {
            startActivity(StripeCardSetupActivity.editLocationIntent(this, thenCheck = true))
        }
        binding.disconnectRow.setOnClickListener { confirmDisconnect() }

        binding.enableToggleRow.setOnClickListener { binding.stripeCardEnableSwitch.performClick() }
        binding.stripeCardEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                // Card payments only turn on through a passing check; the switch follows it.
                setSwitch(false)
                openCheck(enableOnPass = true)
            } else {
                settings.isEnabled = false
                refresh()
            }
        }
    }

    private fun openCheck(enableOnPass: Boolean) {
        startActivity(StripeCardCheckActivity.intent(this, enableOnPass = enableOnPass))
    }

    /** Shows what is already known, then confirms it with the quick checks. */
    internal fun refresh(): Job {
        checkJob?.cancel()
        locationNameJob?.cancel()
        binding.checkProgress.show()
        var hasCompleteConfig = false
        return lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { settings.snapshot() }
                hasCompleteConfig = snapshot.config != null
                setSwitch(snapshot.enabled)
                binding.keyRow.setTrailingText(snapshot.maskedKey)
                showLastChecked(snapshot.lastCheckedAt)
                showLocation(snapshot)
                binding.disconnectRow.visibility = if (snapshot.config != null) View.VISIBLE else View.GONE

                val config = snapshot.config
                if (config == null) {
                    val unsupported = !snapshot.hasStoredKey &&
                        !StripeTerminalManager.isDeviceSupported(this@StripeCardSettingsActivity)
                    show(if (unsupported) State.UNSUPPORTED else State.NOT_SET_UP)
                    return@launch
                }

                loadMissingLocationName(snapshot, config)
                show(knownState(snapshot), readerFailed = snapshot.readerFailed)
                val report = checkerFactory(config).run(includeReader = false)
                val state = if (report.passed) knownState(snapshot) else State.ATTENTION
                show(state, failuresToShow(report), snapshot.readerFailed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // HTTP client exceptions can include request headers.
                Log.e(TAG, "Could not check Stripe settings (${e.javaClass.simpleName})")
                binding.disconnectRow.visibility = if (hasCompleteConfig) View.VISIBLE else View.GONE
                show(State.CHECK_ERROR)
            } finally {
                if (isActive) binding.checkProgress.hide()
            }
        }.also { checkJob = it }
    }

    /** The shop leads the page, and its row repeats the name so it reads as changeable. */
    private fun showLocation(snapshot: Snapshot) {
        val name = snapshot.locationName
        binding.accountTitle.text = name ?: getString(R.string.stripe_card_account_fallback_title)
        val mode = getString(
            if (snapshot.isTestMode) R.string.stripe_card_account_test_mode
            else R.string.stripe_card_account_live_mode
        )
        binding.accountSubtitle.text =
            listOfNotNull(snapshot.locationPlace, mode).joinToString(" · ")
        binding.locationRow.setTrailingText(
            name ?: snapshot.locationId
                .ifEmpty { getString(R.string.stripe_card_location_row_missing) }
        )
    }

    /** When the full check last ran, in the system's own words ("5 min. ago", "Yesterday"). */
    private fun showLastChecked(lastCheckedAt: Long?) {
        binding.checkRow.setTrailingText(
            lastCheckedAt?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                )
            } ?: getString(R.string.stripe_card_last_checked_never)
        )
    }

    /**
     * A location entered by its ID has no name stored. Ask Stripe for it once, so the row shows
     * the shop rather than "tml_…"; the ID stays when the key may not read locations.
     */
    private fun loadMissingLocationName(snapshot: Snapshot, config: StripeCardConfig) {
        if (snapshot.locationName != null) return
        locationNameJob = lifecycleScope.launch {
            val location = withContext(Dispatchers.IO) {
                runCatching { locationsClient.getLocation(config.restrictedKey, config.locationId) }
            }.onFailure { Log.w(TAG, "Could not look up the Stripe location name", it) }.getOrNull()
            // The client falls back to the ID when Stripe has no name; that is not worth storing.
            val name = location?.displayName?.takeIf { it != location.id } ?: return@launch
            if (settings.storedLocationId() != location.id) return@launch
            settings.saveLocationName(name, location.place)
            showLocation(snapshot.copy(locationName = name, locationPlace = location.place))
        }
    }

    /** Where things stood after the last full check. */
    private fun knownState(snapshot: Snapshot): State = when {
        snapshot.readerFailed -> State.ATTENTION
        snapshot.enabled && snapshot.isTestMode -> State.TEST_READY
        snapshot.enabled -> State.READY
        snapshot.passed -> State.PAUSED
        else -> State.UNCHECKED
    }

    /**
     * What the quick check found that the merchant should see here. The app's strip across the
     * top of every screen already says "Offline" or "NFC is off", so those get no card of their
     * own. Without internet the strip only says Offline, and NFC keeps its card.
     */
    private fun failuresToShow(report: CardSetupChecker.Report): List<CardSetupChecker.Outcome.Failed> {
        val offline = !isOnline()
        return report.failures.filterNot {
            when (it.failure) {
                CardSetupChecker.Failure.STRIPE_UNREACHABLE -> offline
                CardSetupChecker.Failure.NFC_OFF -> !offline
                else -> false
            }
        }
    }

    private fun show(
        state: State,
        failures: List<CardSetupChecker.Outcome.Failed> = emptyList(),
        readerFailed: Boolean = false,
    ) {
        val configured = state != State.UNSUPPORTED && state != State.NOT_SET_UP
        binding.configuredContainer.visibility = if (configured) View.VISIBLE else View.GONE
        binding.introContainer.visibility = if (state == State.NOT_SET_UP) View.VISIBLE else View.GONE
        binding.getStartedButton.visibility = binding.introContainer.visibility
        animateConnectDots(run = state == State.NOT_SET_UP)
        // The connect screen carries its own headline, so the status block would only repeat it.
        binding.statusCard.visibility = if (state == State.NOT_SET_UP) View.GONE else View.VISIBLE
        binding.accountHeader.visibility = binding.configuredContainer.visibility

        binding.attentionList.removeAllViews()
        when (state) {
            State.UNSUPPORTED -> addCard(
                R.drawable.ic_warning, R.color.color_warning,
                getString(R.string.stripe_card_headline_unsupported),
                getString(R.string.stripe_card_body_unsupported),
            )
            // All is well: the Status row says so, and a card would only repeat it.
            State.READY, State.TEST_READY, State.PAUSED -> Unit
            State.UNCHECKED -> addCard(
                R.drawable.ic_info, R.color.color_text_secondary,
                getString(R.string.stripe_card_headline_unchecked),
                getString(R.string.stripe_card_body_unchecked),
                R.string.stripe_card_check_row_title,
            ) { openCheck(enableOnPass = false) }
            State.ATTENTION -> showFailures(failures)
            State.CHECK_ERROR -> addCard(
                R.drawable.ic_warning, R.color.color_warning,
                getString(R.string.stripe_card_check_incomplete_title),
                getString(R.string.stripe_card_check_incomplete_body),
                R.string.stripe_card_fix_retry,
            ) { refresh() }
            State.NOT_SET_UP -> Unit
        }
        if (readerFailed) {
            addCard(
                R.drawable.ic_warning, R.color.color_warning,
                getString(R.string.stripe_card_check_problem_reader),
                getString(R.string.stripe_card_check_reader_retry_body),
                R.string.stripe_card_check_row_title,
            ) { openCheck(enableOnPass = false) }
        }
        showStatus(state)
    }

    private fun showStatus(state: State) {
        val (text, color) = when (state) {
            State.READY -> R.string.stripe_card_status_ready to R.color.color_success_green
            State.TEST_READY -> R.string.stripe_card_status_test_ready to R.color.color_success_green
            State.PAUSED -> R.string.stripe_card_status_paused to R.color.color_text_secondary
            State.ATTENTION, State.CHECK_ERROR -> R.string.stripe_card_status_attention to R.color.color_warning
            else -> R.string.stripe_card_status_unchecked to R.color.color_text_secondary
        }
        binding.statusRow.setTrailingText(getString(text))
        binding.statusRow.setTrailingTextColor(ContextCompat.getColor(this, color))
    }

    /** One card per failed check: what is wrong, why it matters, and the button that fixes it. */
    private fun showFailures(failures: List<CardSetupChecker.Outcome.Failed>) {
        failures.forEach { failure ->
            addCard(
                R.drawable.ic_warning, R.color.color_warning,
                getString(CardCheckText.failureTitle(failure.failure)),
                CardCheckText.problemBody(this, failure),
                CardCheckText.fixLabel(failure.fix, locationFix),
            ) { applyFix(failure.fix) }
        }
    }

    /** Adds a card to the status slot. [action] is its button, or null for a card that only informs. */
    private fun addCard(
        @DrawableRes icon: Int,
        @ColorRes tint: Int,
        title: String,
        body: String,
        @StringRes action: Int? = null,
        onAction: () -> Unit = {},
    ) {
        val card = ItemCardAttentionBinding.inflate(layoutInflater, binding.attentionList, true)
        card.attentionIcon.setImageResource(icon)
        ImageViewCompat.setImageTintList(card.attentionIcon, ContextCompat.getColorStateList(this, tint))
        card.attentionTitle.text = title
        card.attentionBody.text = body
        card.attentionFixButton.visibility = if (action == null) View.GONE else View.VISIBLE
        // Without the button under it, the text needs the room the button's own padding gave.
        card.attentionBody.updatePadding(
            bottom = if (action == null) resources.getDimensionPixelSize(R.dimen.space_m) else 0
        )
        if (action != null) {
            card.attentionFixButton.setText(action)
            // Amber is for problems; a card that only suggests the next step uses the text colour.
            card.attentionFixButton.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (tint == R.color.color_warning) R.color.color_warning else R.color.color_text_primary,
                )
            )
            card.attentionFixButton.setOnClickListener { onAction() }
        }
    }

    /** Coming back from any of these resumes the page, which runs the quick checks again. */
    private fun applyFix(fix: Fix) {
        when (fix) {
            Fix.NONE -> Unit
            Fix.RETRY -> refresh()
            Fix.OPEN_NFC_SETTINGS -> leaveToFix(Intent(Settings.ACTION_NFC_SETTINGS))
            Fix.GRANT_LOCATION -> locationFix.ask()
            Fix.REPLACE_KEY ->
                leaveToFix(StripeCardSetupActivity.editKeyIntent(this, thenCheck = true))
            Fix.CHOOSE_LOCATION ->
                leaveToFix(StripeCardSetupActivity.editLocationIntent(this, thenCheck = true))
            Fix.OPEN_ABOUT -> leaveToFix(Intent(this, AboutActivity::class.java))
            Fix.OPEN_DEVELOPER_SETTINGS ->
                leaveToFix(Intent(this, DeveloperSettingsActivity::class.java))
        }
    }

    private fun leaveToFix(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${intent.action ?: intent.component}", e)
        }
    }

    private fun setSwitch(checked: Boolean) {
        isUpdatingSwitch = true
        binding.stripeCardEnableSwitch.isChecked = checked
        isUpdatingSwitch = false
    }

    private fun confirmDisconnect() {
        DialogHelper.showConfirmation(
            this,
            DialogHelper.ConfirmationConfig(
                title = getString(R.string.stripe_card_disconnect_title),
                message = getString(R.string.stripe_card_disconnect_message),
                confirmText = getString(R.string.stripe_card_disconnect_confirm),
                isDestructive = true,
                onConfirm = {
                    settings.clear()
                    StripeTerminalManager.reset()
                    Toast.makeText(this, R.string.stripe_card_removed, Toast.LENGTH_SHORT).show()
                    refresh()
                },
            ),
        )
    }

    companion object {
        private const val TAG = "StripeCardSettings"
        private const val DOTS_CYCLE_MS = 1800L
        private const val DOT_MIN_ALPHA = 0.25f

        fun intent(context: android.content.Context): Intent =
            Intent(context, StripeCardSettingsActivity::class.java)
    }
}
