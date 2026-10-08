package com.electricdreams.numo.feature.offline

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.accessibility.AccessibilityManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.electricdreams.numo.R
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.databinding.ActivityOfflineExplainerBinding
import com.electricdreams.numo.databinding.ItemOfflineCapabilityBinding
import com.electricdreams.numo.databinding.ItemOfflineCapabilityGroupBinding
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Feature
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Group
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Row
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Status
import com.electricdreams.numo.ui.util.applySettingsWindowInsets
import com.electricdreams.numo.util.overridePendingTransitionCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen answer to "why can't I charge?": what needs internet, then what still works.
 *
 * If the connection returns while it's open, it flips to "You're back online.", ticks the rows
 * green, and closes itself (unless TalkBack is on, where a time limit would cut the user off).
 */
class OfflineExplainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineExplainerBinding
    private val rowViews = mutableListOf<Pair<Row, ItemOfflineCapabilityBinding>>()
    private var celebrating = false
    private var autoCloseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineExplainerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        binding.closeButton.setOnClickListener { finish() }
        val paymentOnScreen = intent.getBooleanExtra(EXTRA_PAYMENT_ON_SCREEN, false)
        renderGroups(OfflineCapabilities.groups(OfflineCapabilities.Config.from(this, paymentOnScreen)))
        observeConnectivity()
    }

    private fun observeConnectivity() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var sawOffline = false
                ConnectivityMonitor.getInstance(this@OfflineExplainerActivity).isOnline
                    .collect { online ->
                        when {
                            !online -> {
                                sawOffline = true
                                restoreOffline()
                            }
                            sawOffline -> celebrate()
                            // Opened while already online: nothing to explain.
                            else -> finish()
                        }
                    }
            }
        }
    }

    private fun renderGroups(groups: Map<Group, List<Row>>) {
        binding.capabilityGroups.removeAllViews()
        rowViews.clear()
        Group.entries.forEach { group ->
            val rows = groups[group].orEmpty()
            if (rows.isEmpty()) return@forEach
            val groupBinding = ItemOfflineCapabilityGroupBinding.inflate(
                layoutInflater, binding.capabilityGroups, true
            )
            groupBinding.groupTitle.setText(
                when (group) {
                    Group.NEEDS_INTERNET -> R.string.offline_explainer_group_needs_internet
                    Group.WORKS_OFFLINE -> R.string.offline_explainer_group_works_offline
                }
            )
            rows.forEach { row ->
                val item = ItemOfflineCapabilityBinding.inflate(
                    layoutInflater, groupBinding.groupRows, true
                )
                item.capabilityTitle.setText(titleFor(row.feature))
                item.capabilityIcon.setImageResource(iconFor(row.status))
                setStatus(row, item, statusTextFor(row), outdated = row.feature == Feature.EXCHANGE_RATE && row.status == Status.DEGRADED)
                rowViews += row to item
            }
        }
    }

    private fun setStatus(row: Row, item: ItemOfflineCapabilityBinding, status: String, outdated: Boolean) {
        item.capabilityStatus.text = status
        val title = getString(titleFor(row.feature))
        item.root.contentDescription = getString(
            if (outdated) R.string.offline_row_content_description_outdated
            else R.string.offline_row_content_description,
            title,
            status
        )
    }

    private fun restoreOffline() {
        if (!celebrating) return
        celebrating = false
        autoCloseJob?.cancel()
        autoCloseJob = null

        restoreIcon(binding.heroBadge, R.drawable.ic_status_degraded)
        restoreText(binding.title, getString(R.string.offline_explainer_title))
        restoreText(binding.subtitle, getString(R.string.offline_explainer_subtitle))
        rowViews.forEach { (row, item) ->
            restoreIcon(item.capabilityIcon, iconFor(row.status))
            val status = statusTextFor(row)
            restoreText(item.capabilityStatus, status)
            setStatus(
                row, item, status,
                outdated = row.feature == Feature.EXCHANGE_RATE && row.status == Status.DEGRADED
            )
        }
    }

    private fun restoreIcon(view: ImageView, @DrawableRes icon: Int) {
        view.animate().cancel()
        view.setImageResource(icon)
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun restoreText(view: TextView, text: CharSequence) {
        view.animate().cancel()
        view.text = text
        view.alpha = 1f
        view.translationY = 0f
    }

    private fun celebrate() {
        if (celebrating) return
        celebrating = true

        swapIcon(binding.heroBadge, R.drawable.ic_status_available, startDelay = 0L)
        crossfadeText(binding.title, getString(R.string.offline_explainer_back_online_title))
        crossfadeText(binding.subtitle, getString(R.string.offline_explainer_back_online_subtitle))

        var delayMs = ROW_STAGGER_START_MS
        rowViews.filter { (row, _) -> row.status != Status.AVAILABLE }.forEach { (row, item) ->
            // Degraded rows (rate, code on screen) aren't fresh the instant we reconnect
            val status = getString(
                if (row.status == Status.DEGRADED) R.string.offline_status_updating
                else R.string.offline_status_available
            )
            swapIcon(item.capabilityIcon, R.drawable.ic_status_available, startDelay = delayMs)
            crossfadeText(item.capabilityStatus, status, startDelay = delayMs)
            setStatus(row, item, status, outdated = false)
            delayMs += ROW_STAGGER_MS
        }

        val touchExploration = getSystemService<AccessibilityManager>()?.isTouchExplorationEnabled == true
        if (touchExploration) return
        autoCloseJob = lifecycleScope.launch {
            delay(AUTO_CLOSE_MS)
            finish()
            overridePendingTransitionCompat(0, R.anim.fade_out)
        }
    }

    private fun swapIcon(view: ImageView, @DrawableRes icon: Int, startDelay: Long) {
        view.animate().scaleX(0f).scaleY(0f).setStartDelay(startDelay).setDuration(ICON_OUT_MS)
            .withEndAction {
                view.setImageResource(icon)
                view.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(ICON_IN_MS)
                    .setInterpolator(OvershootInterpolator(ICON_OVERSHOOT))
                    .start()
            }
            .start()
    }

    private fun crossfadeText(view: TextView, text: CharSequence, startDelay: Long = 0L) {
        view.animate().alpha(0f).setStartDelay(startDelay).setDuration(TEXT_OUT_MS)
            .withEndAction {
                view.text = text
                view.translationY = view.resources.displayMetrics.density * TEXT_RISE_DP
                view.animate().alpha(1f).translationY(0f).setStartDelay(0L)
                    .setDuration(TEXT_IN_MS)
                    .start()
            }
            .start()
    }

    @StringRes
    private fun titleFor(feature: Feature): Int = when (feature) {
        Feature.NEW_CHARGES -> R.string.offline_feature_new_charges
        Feature.CATALOG -> R.string.offline_feature_catalog
        Feature.WITHDRAWALS -> R.string.offline_feature_withdrawals
        Feature.AUTO_WITHDRAW -> R.string.offline_feature_auto_withdraw
        Feature.PAYMENT_ON_SCREEN -> R.string.offline_feature_payment_on_screen
        Feature.HISTORY -> R.string.offline_feature_history
        Feature.EXCHANGE_RATE -> R.string.offline_feature_exchange_rate
    }

    private fun statusTextFor(row: Row): String = when {
        row.status == Status.AVAILABLE -> getString(R.string.offline_status_available)
        row.updatedAt != null ->
            getString(R.string.offline_status_using_rate, formatUpdatedAt(row.updatedAt))
        row.feature == Feature.PAYMENT_ON_SCREEN -> getString(R.string.offline_status_confirms_later)
        row.feature == Feature.AUTO_WITHDRAW -> getString(R.string.offline_status_paused)
        else -> getString(R.string.offline_status_unavailable)
    }

    private fun formatUpdatedAt(timestamp: Long): String {
        val flags = if (DateUtils.isToday(timestamp)) {
            DateUtils.FORMAT_SHOW_TIME
        } else {
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        }
        return DateUtils.formatDateTime(this, timestamp, flags)
    }

    @DrawableRes
    private fun iconFor(status: Status): Int = when (status) {
        Status.AVAILABLE -> R.drawable.ic_status_available
        Status.DEGRADED -> R.drawable.ic_status_degraded
        Status.UNAVAILABLE -> R.drawable.ic_status_unavailable
    }

    companion object {
        private const val EXTRA_PAYMENT_ON_SCREEN = "payment_on_screen"
        private const val ROW_STAGGER_START_MS = 120L
        private const val ROW_STAGGER_MS = 60L
        private const val ICON_OUT_MS = 120L
        private const val ICON_IN_MS = 320L
        private const val ICON_OVERSHOOT = 1.4f
        private const val TEXT_OUT_MS = 120L
        private const val TEXT_IN_MS = 220L
        private const val TEXT_RISE_DP = 6f
        private const val AUTO_CLOSE_MS = 1_500L

        /** [paymentOnScreen]: opened from a payment request whose code can still be paid. */
        fun start(context: Context, paymentOnScreen: Boolean = false) {
            context.startActivity(
                Intent(context, OfflineExplainerActivity::class.java)
                    .putExtra(EXTRA_PAYMENT_ON_SCREEN, paymentOnScreen)
            )
        }
    }
}
