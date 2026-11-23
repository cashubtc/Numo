package com.electricdreams.numo.feature.settings

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityStripeCardSettingsBinding
import com.electricdreams.numo.payment.card.AndroidCardSetupEnvironment
import com.electricdreams.numo.payment.card.CardSetupChecker
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
    private lateinit var settings: StripeCardSettings

    /** Replaceable so tests can decide what the phone and Stripe report. */
    internal var checkerFactory: () -> CardSetupChecker = {
        CardSetupChecker(AndroidCardSetupEnvironment(this))
    }

    /** Replaceable so tests can stand in for Stripe. */
    internal var locationsClient = StripeLocationsClient()

    private var isUpdatingSwitch = false
    private var checkJob: Job? = null
    private var locationNameJob: Job? = null

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
    private fun refresh() {
        checkJob?.cancel()
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
                checkJob = lifecycleScope.launch {
                    val report = checkerFactory().run(includeReader = false)
                    show(settledState(report), report)
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

    private fun settledState(report: CardSetupChecker.Report): State = when {
        !report.passed -> State.ATTENTION
        settings.isEnabled -> State.READY
        settings.hasPassedCheck -> State.PAUSED
        else -> State.UNCHECKED
    }

    private fun show(state: State, report: CardSetupChecker.Report? = null) {
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
                val known = if (settings.isEnabled) State.READY
                else if (settings.hasPassedCheck) State.PAUSED else State.UNCHECKED
                show(known)
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

        showFailures(report?.failures.orEmpty())
        val needsCheck = state == State.ATTENTION || state == State.UNCHECKED
        binding.fixButton.visibility = if (needsCheck) View.VISIBLE else View.GONE
        binding.fixButton.setText(
            if (state == State.UNCHECKED) R.string.stripe_card_check_row_title
            else R.string.stripe_card_see_fixes
        )
    }

    private fun showFailures(failures: List<CardSetupChecker.Outcome.Failed>) {
        binding.attentionList.removeAllViews()
        binding.attentionList.visibility = if (failures.isEmpty()) View.GONE else View.VISIBLE
        failures.forEach { failure ->
            val line = TextView(this).apply {
                text = CardCheckText.failed(this@StripeCardSettingsActivity, failure)
                TextViewCompat.setTextAppearance(this, R.style.Text_SettingsBody)
                setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
                TextViewCompat.setCompoundDrawableTintList(
                    this,
                    ContextCompat.getColorStateList(context, R.color.color_warning),
                )
                compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.space_m)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_s), 0, 0)
            }
            binding.attentionList.addView(line)
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
