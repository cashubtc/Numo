package com.electricdreams.numo.feature.settings

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import kotlin.math.PI
import kotlin.math.cos
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
import com.electricdreams.numo.payment.card.StripeCardSettings
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

    private enum class State { UNSUPPORTED, NOT_SET_UP, CHECKING, READY, PAUSED, UNCHECKED, ATTENTION }

    private lateinit var binding: ActivityStripeCardSettingsBinding

    /** Replaceable so tests can store a key without the Android Keystore. */
    internal lateinit var settings: StripeCardSettings

    /** Replaceable so tests can decide what the phone and Stripe report. */
    internal var checkerFactory: () -> CardSetupChecker = {
        CardSetupChecker(AndroidCardSetupEnvironment(this))
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
        binding.fixButton.setOnClickListener { openCheck(enableOnPass = false) }
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
    internal fun refresh() {
        checkJob?.cancel()
        binding.checkProgress.hide()
        setSwitch(settings.isEnabled)
        binding.keyRow.setSubtitle(settings.maskedKey())
        showLocation()
        loadMissingLocationName()

        when {
            !StripeTerminalManager.isDeviceSupported(this) && !settings.hasStoredKey() ->
                show(State.UNSUPPORTED)
            settings.loadConfig() == null -> show(State.NOT_SET_UP)
            else -> {
                show(State.CHECKING)
                // Says the state above is still being confirmed; hidden again before a quick
                // check could make it flash.
                binding.checkProgress.show()
                checkJob = lifecycleScope.launch {
                    val report = try {
                        checkerFactory().run(includeReader = false)
                    } finally {
                        // A check cancelled by the next refresh must not hide that one's progress.
                        if (isActive) binding.checkProgress.hide()
                    }
                    val failures = failuresToShow(report)
                    show(if (failures.isEmpty()) knownState() else State.ATTENTION, failures)
                }
            }
        }
    }

    private fun showLocation() {
        binding.locationRow.setSubtitle(
            settings.storedLocationName()
                ?: settings.storedLocationId().ifEmpty { getString(R.string.stripe_card_location_row_missing) }
        )
    }

    /**
     * A location entered by its ID has no name stored. Ask Stripe for it once, so the row shows
     * the shop rather than "tml_…"; the ID stays when the key may not read locations.
     */
    private fun loadMissingLocationName() {
        locationNameJob?.cancel()
        if (settings.storedLocationName() != null) return
        val config = settings.loadConfig() ?: return
        locationNameJob = lifecycleScope.launch {
            val location = withContext(Dispatchers.IO) {
                runCatching { locationsClient.getLocation(config.restrictedKey, config.locationId) }
            }.onFailure { Log.w(TAG, "Could not look up the Stripe location name", it) }.getOrNull()
            // The client falls back to the ID when Stripe has no name; that is not worth storing.
            val name = location?.displayName?.takeIf { it != location.id } ?: return@launch
            if (settings.storedLocationId() != location.id) return@launch
            settings.saveLocationName(name)
            showLocation()
        }
    }

    /** Where things stood after the last full check. */
    private fun knownState(): State = when {
        settings.isEnabled -> State.READY
        settings.hasPassedCheck -> State.PAUSED
        else -> State.UNCHECKED
    }

    /**
     * What the quick check found that the merchant should see. Without internet, "Couldn't reach
     * Stripe" is left out: the app's Offline strip already says so on every screen.
     */
    private fun failuresToShow(report: CardSetupChecker.Report): List<CardSetupChecker.Outcome.Failed> {
        val offline = !isOnline()
        return report.failures.filterNot {
            offline && it.failure == CardSetupChecker.Failure.STRIPE_UNREACHABLE
        }
    }

    private fun show(state: State, failures: List<CardSetupChecker.Outcome.Failed> = emptyList()) {
        val configured = state != State.UNSUPPORTED && state != State.NOT_SET_UP
        binding.configuredContainer.visibility = if (configured) View.VISIBLE else View.GONE
        binding.introContainer.visibility = if (state == State.NOT_SET_UP) View.VISIBLE else View.GONE
        binding.getStartedButton.visibility = binding.introContainer.visibility
        animateConnectDots(run = state == State.NOT_SET_UP)
        // The connect screen carries its own headline, so the status block would only repeat it.
        binding.statusCard.visibility = if (state == State.NOT_SET_UP) View.GONE else View.VISIBLE

        when (state) {
            State.UNSUPPORTED -> setStatus(
                R.string.stripe_card_headline_unsupported, R.string.stripe_card_body_unsupported,
            )
            State.NOT_SET_UP -> Unit
            State.CHECKING -> {
                // Show the last known state while the quick checks run: nothing flashes or resizes
                // unless the answer actually changed.
                show(knownState())
                return
            }
            State.READY -> setStatus(
                R.string.stripe_card_headline_ready, R.string.stripe_card_body_ready,
            )
            State.PAUSED -> setStatus(
                R.string.stripe_card_headline_paused, R.string.stripe_card_body_paused,
            )
            State.UNCHECKED -> setStatus(
                R.string.stripe_card_headline_unchecked, R.string.stripe_card_body_unchecked,
            )
            State.ATTENTION -> setStatus(
                R.string.stripe_card_headline_attention, R.string.stripe_card_body_attention,
            )
        }

        binding.testModeBanner.visibility =
            if (configured && settings.isTestMode() == true) View.VISIBLE else View.GONE

        showFailures(failures)
        // A green tick beside "Check setup" would contradict the cards above it.
        val failing = state == State.ATTENTION
        binding.checkRow.setIcon(if (failing) R.drawable.ic_warning else R.drawable.ic_checkmark_circle)
        // m3_primary is the tint Widget.Numo.SettingsRow gives every row icon.
        binding.checkRow.setIconTint(
            ContextCompat.getColorStateList(
                this,
                if (failing) R.color.color_warning else R.color.m3_primary,
            )
        )
        // Each problem card says what is wrong and fixes it, so the headline has nothing to add.
        val cardsSayIt = state == State.ATTENTION && failures.isNotEmpty()
        binding.statusHeadline.visibility = if (cardsSayIt) View.GONE else View.VISIBLE
        binding.statusBody.visibility = binding.statusHeadline.visibility
        val needsCheck = (state == State.ATTENTION && !cardsSayIt) || state == State.UNCHECKED
        binding.fixButton.visibility = if (needsCheck) View.VISIBLE else View.GONE
        binding.fixButton.setText(
            if (state == State.UNCHECKED) R.string.stripe_card_check_row_title
            else R.string.stripe_card_see_fixes
        )
    }

    /** One card per failed check: what is wrong, why it matters, and the button that fixes it. */
    private fun showFailures(failures: List<CardSetupChecker.Outcome.Failed>) {
        binding.attentionList.removeAllViews()
        binding.attentionList.visibility = if (failures.isEmpty()) View.GONE else View.VISIBLE
        failures.forEach { failure ->
            val card = ItemCardAttentionBinding.inflate(layoutInflater, binding.attentionList, true)
            card.attentionTitle.setText(CardCheckText.failureTitle(failure.failure))
            card.attentionBody.text = CardCheckText.problemBody(this, failure)
            val label = CardCheckText.fixLabel(failure.fix)
            card.attentionFixButton.visibility = if (label == null) View.GONE else View.VISIBLE
            if (label != null) {
                card.attentionFixButton.setText(label)
                card.attentionFixButton.setOnClickListener { applyFix(failure.fix) }
            }
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

    private fun setStatus(@StringRes headline: Int, @StringRes body: Int) {
        binding.statusHeadline.setText(headline)
        binding.statusBody.setText(body)
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
