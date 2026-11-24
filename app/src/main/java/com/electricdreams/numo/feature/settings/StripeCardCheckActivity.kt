package com.electricdreams.numo.feature.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityStripeCardCheckBinding
import com.electricdreams.numo.databinding.ItemCardCheckStepBinding
import com.electricdreams.numo.payment.card.AndroidCardSetupEnvironment
import com.electricdreams.numo.payment.card.CardSetupChecker
import com.electricdreams.numo.payment.card.CardSetupChecker.Check
import com.electricdreams.numo.payment.card.CardSetupChecker.Fix
import com.electricdreams.numo.payment.card.CardSetupChecker.Group
import com.electricdreams.numo.payment.card.CardSetupChecker.Outcome
import com.electricdreams.numo.payment.card.StripeCardSettings
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Runs the full card setup check, one thing at a time: three steps across the top show where
 * it is, and the screen only stops to hand the merchant a single problem with its fix. Card
 * payments are switched on here, and only by a full pass.
 */
class StripeCardCheckActivity : AppCompatActivity() {

    private enum class StepState { PENDING, RUNNING, PASSED, FAILED }

    private enum class Orb(@ColorRes val fill: Int, @ColorRes val tint: Int) {
        RUNNING(R.color.color_bg_surface, R.color.color_text_primary),
        PASSED(R.color.color_status_success_bg, R.color.color_success_green),
        FAILED(R.color.color_status_pending_bg, R.color.color_warning),
    }

    private lateinit var binding: ActivityStripeCardCheckBinding
    private lateinit var settings: StripeCardSettings
    private val outcomes = mutableMapOf<Check, Outcome>()

    /** Replaceable so tests can decide what the phone and Stripe report. */
    internal var checkerFactory: () -> CardSetupChecker = {
        CardSetupChecker(AndroidCardSetupEnvironment(this))
    }

    private var checkJob: Job? = null

    /** Set when the merchant leaves to fix something, so the check re-runs when they return. */
    private var rerunOnResume = false
    private var shownIcon = 0

    private val locationFix = LocationPermissionFix(
        this,
        onLeaveForSettings = { rerunOnResume = true },
        onAnswered = { runCheck() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStripeCardCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        settings = StripeCardSettings.getInstance(this)
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.secondaryButton.setOnClickListener { runCheck() }
        runCheck()
    }

    override fun onResume() {
        super.onResume()
        if (rerunOnResume) {
            rerunOnResume = false
            runCheck()
        }
    }

    internal fun runCheck() {
        if (checkJob?.isActive == true) return
        outcomes.clear()
        renderSteps()
        showRunning(null)

        checkJob = lifecycleScope.launch {
            val revision = settings.configurationRevision
            val report = try {
                checkerFactory().run(includeReader = true) { check, outcome ->
                    outcomes[check] = outcome
                    renderSteps()
                    if (outcome == Outcome.Running) showRunning(check)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Setup check failed unexpectedly", e)
                CardSetupChecker.Report(emptyMap())
            }
            val requestedEnable = intent.getBooleanExtra(EXTRA_ENABLE_ON_PASS, false)
            if (settings.recordFullCheck(revision, report, requestedEnable)) onFinished(report)
            else showIncomplete()
        }
    }

    private fun showRunning(check: Check?) {
        showOrb(Orb.RUNNING, groupIcon(check?.group ?: Group.PHONE))
        showText(
            getString(check?.let(CardCheckText::running) ?: R.string.stripe_card_check_summary_running),
            "",
        )
        showProblemDots(0)
        binding.primaryButton.isEnabled = false
        binding.primaryButton.setText(R.string.stripe_card_check_summary_running)
        binding.secondaryButton.visibility = View.INVISIBLE
    }

    private fun onFinished(report: CardSetupChecker.Report) {
        binding.primaryButton.isEnabled = true
        when {
            report.passed -> showPassed(report.isTestMode)
            // The merchant fixes one problem at a time; the check re-runs after each fix.
            else -> report.outcomes.values.filterIsInstance<Outcome.Failed>().let { failures ->
                failures.firstOrNull()?.let { showProblem(it, failures.size) } ?: showIncomplete()
            }
        }
    }

    private fun showPassed(testMode: Boolean) {
        showOrb(Orb.PASSED, R.drawable.ic_check)
        showText(
            getString(
                when {
                    testMode -> R.string.stripe_card_check_test_passed
                    settings.isEnabled -> R.string.stripe_card_check_ready
                    else -> R.string.stripe_card_check_summary_passed
                }
            ),
            getString(
                when {
                    !settings.isEnabled -> R.string.stripe_card_check_summary_passed_paused_body
                    testMode -> R.string.stripe_card_check_test_body
                    else -> R.string.stripe_card_check_summary_passed_body
                }
            ),
        )
        showProblemDots(0)
        binding.primaryButton.setText(R.string.stripe_card_check_done)
        binding.primaryButton.setOnClickListener { finish() }
        binding.secondaryButton.visibility = View.INVISIBLE
    }

    private fun showProblem(problem: Outcome.Failed, problemCount: Int) {
        showOrb(Orb.FAILED, R.drawable.ic_exclamation)
        showText(
            getString(CardCheckText.failureTitle(problem.failure)),
            CardCheckText.problemBody(this, problem),
        )
        showProblemDots(problemCount)

        val fixLabel = CardCheckText.fixLabel(problem.fix, locationFix)
        if (fixLabel == null) {
            // Nothing can be done on this phone, so the only way on is out.
            binding.primaryButton.setText(R.string.stripe_card_check_done)
            binding.primaryButton.setOnClickListener { finish() }
        } else {
            binding.primaryButton.setText(fixLabel)
            binding.primaryButton.setOnClickListener { applyFix(problem.fix) }
        }
        // "Try again" already re-runs the check; a second button for the same thing is noise.
        val offerRerun = fixLabel != null && problem.fix != Fix.RETRY
        binding.secondaryButton.visibility = if (offerRerun) View.VISIBLE else View.INVISIBLE
    }

    /** The check itself broke, which says nothing about the setup. */
    private fun showIncomplete() {
        binding.primaryButton.isEnabled = true
        showOrb(Orb.FAILED, R.drawable.ic_exclamation)
        showText(
            getString(R.string.stripe_card_check_incomplete_title),
            getString(R.string.stripe_card_check_incomplete_body),
        )
        showProblemDots(0)
        binding.primaryButton.setText(R.string.stripe_card_fix_retry)
        binding.primaryButton.setOnClickListener { runCheck() }
        binding.secondaryButton.visibility = View.INVISIBLE
    }

    private fun renderSteps() {
        Group.entries.forEach { group ->
            val step = stepView(group)
            val checks = Check.entries.filter { it.group == group }
            val results = checks.map { outcomes[it] }
            val state = when {
                results.any { it is Outcome.Failed } -> StepState.FAILED
                results.any { it == Outcome.Running } -> StepState.RUNNING
                results.all { it == Outcome.Passed } -> StepState.PASSED
                else -> StepState.PENDING
            }
            val settled = results.count { it == Outcome.Passed || it is Outcome.Failed }

            step.stepProgress.setIndicatorColor(
                color(if (state == StepState.FAILED) R.color.color_warning else R.color.color_success_green)
            )
            step.stepProgress.setProgressCompat(settled * PROGRESS_MAX / checks.size, true)
            step.stepSpinner.visibility = if (state == StepState.RUNNING) View.VISIBLE else View.INVISIBLE
            step.stepBadge.visibility = if (state == StepState.RUNNING) View.INVISIBLE else View.VISIBLE
            step.stepBadge.setImageResource(
                when (state) {
                    StepState.PASSED -> R.drawable.ic_step_badge_passed
                    StepState.FAILED -> R.drawable.ic_step_badge_failed
                    else -> R.drawable.bg_step_badge_pending
                }
            )
            step.stepLabel.setText(stepLabel(group))
            step.stepLabel.setTextColor(
                color(
                    when (state) {
                        StepState.RUNNING, StepState.FAILED -> R.color.color_text_primary
                        StepState.PASSED -> R.color.color_text_secondary
                        StepState.PENDING -> R.color.color_text_tertiary
                    }
                )
            )
            step.root.contentDescription = getString(
                when (state) {
                    StepState.PENDING -> R.string.stripe_card_check_step_pending
                    StepState.RUNNING -> R.string.stripe_card_check_step_running
                    StepState.PASSED -> R.string.stripe_card_check_step_passed
                    StepState.FAILED -> R.string.stripe_card_check_step_failed
                },
                getString(stepLabel(group)),
            )
        }
    }

    private fun stepView(group: Group): ItemCardCheckStepBinding = when (group) {
        Group.PHONE -> binding.stepPhone
        Group.STRIPE -> binding.stepStripe
        Group.READER -> binding.stepReader
    }

    @StringRes
    private fun stepLabel(group: Group): Int = when (group) {
        Group.PHONE -> R.string.stripe_card_check_step_phone
        Group.STRIPE -> R.string.stripe_card_check_step_stripe
        Group.READER -> R.string.stripe_card_check_step_reader
    }

    @DrawableRes
    private fun groupIcon(group: Group): Int = when (group) {
        Group.PHONE -> R.drawable.ic_phone_outline
        Group.STRIPE -> R.drawable.ic_key
        Group.READER -> R.drawable.ic_contactless
    }

    private fun showOrb(orb: Orb, @DrawableRes icon: Int) {
        binding.orbFill.backgroundTintList = ColorStateList.valueOf(color(orb.fill))
        binding.orbRing.visibility = if (orb == Orb.RUNNING) View.VISIBLE else View.INVISIBLE
        ImageViewCompat.setImageTintList(binding.orbIcon, ColorStateList.valueOf(color(orb.tint)))
        if (icon == shownIcon) return
        shownIcon = icon
        binding.orbIcon.setImageResource(icon)
        settle(binding.orbIcon, fromScale = ICON_START_SCALE)
    }

    /** Text that says something new fades up into place; unchanged text stays still. */
    private fun showText(title: String, body: String) {
        if (binding.summaryTitle.text.toString() != title) {
            binding.summaryTitle.text = title
            settle(binding.summaryTitle, fromOffset = true)
        }
        if (binding.summaryBody.text.toString() != body) {
            binding.summaryBody.text = body
            if (body.isNotEmpty()) settle(binding.summaryBody, fromOffset = true)
        }
    }

    private fun settle(view: View, fromScale: Float = 1f, fromOffset: Boolean = false) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.translationY =
            if (fromOffset) resources.getDimension(R.dimen.space_s) else 0f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(SETTLE_MS)
            .setInterpolator(EASE_OUT)
            .start()
    }

    /** The first dot is lit: problems are handled from the top, one at a time. */
    private fun showProblemDots(count: Int) {
        binding.problemDots.removeAllViews()
        binding.problemDots.visibility = if (count > 1) View.VISIBLE else View.GONE
        if (count <= 1) return
        binding.problemDots.contentDescription =
            getString(R.string.stripe_card_check_problem_position, 1, count)
        val size = resources.getDimensionPixelSize(R.dimen.space_s) * 3 / 4
        repeat(count) { index ->
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_connect_dot)
                backgroundTintList = ColorStateList.valueOf(
                    color(if (index == 0) R.color.color_text_primary else R.color.color_text_quaternary)
                )
            }
            binding.problemDots.addView(
                dot,
                LinearLayout.LayoutParams(size, size).apply { marginStart = if (index == 0) 0 else size },
            )
        }
    }

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private fun applyFix(fix: Fix) {
        when (fix) {
            Fix.NONE -> Unit
            Fix.RETRY -> runCheck()
            Fix.OPEN_NFC_SETTINGS -> leaveToFix(Intent(Settings.ACTION_NFC_SETTINGS))
            Fix.GRANT_LOCATION -> locationFix.ask()
            Fix.REPLACE_KEY ->
                leaveToFix(StripeCardSetupActivity.editKeyIntent(this, thenCheck = false))
            Fix.CHOOSE_LOCATION ->
                leaveToFix(StripeCardSetupActivity.editLocationIntent(this, thenCheck = false))
            Fix.OPEN_ABOUT -> leaveToFix(Intent(this, AboutActivity::class.java))
            Fix.OPEN_DEVELOPER_SETTINGS ->
                leaveToFix(Intent(this, DeveloperSettingsActivity::class.java))
        }
    }

    private fun leaveToFix(intent: Intent) {
        try {
            rerunOnResume = true
            startActivity(intent)
        } catch (e: Exception) {
            rerunOnResume = false
            Log.w(TAG, "Could not open ${intent.action ?: intent.component}", e)
        }
    }

    companion object {
        private const val TAG = "StripeCardCheck"
        private const val PROGRESS_MAX = 100
        private const val SETTLE_MS = 220L
        private const val ICON_START_SCALE = 0.85f

        /** A strong ease-out: quick to start, soft to land. */
        private val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)
        private const val EXTRA_ENABLE_ON_PASS = "enable_on_pass"

        /**
         * @param enableOnPass true when the merchant asked to turn card payments on, so a pass
         *   switches them on even if they were paused.
         */
        fun intent(context: Context, enableOnPass: Boolean): Intent =
            Intent(context, StripeCardCheckActivity::class.java)
                .putExtra(EXTRA_ENABLE_ON_PASS, enableOnPass)
    }
}
