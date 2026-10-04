package com.electricdreams.numo.feature.offline

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.electricdreams.numo.R
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.databinding.ActivityOfflineExplainerBinding
import com.electricdreams.numo.databinding.ItemOfflineCapabilityBinding
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Feature
import com.electricdreams.numo.feature.offline.OfflineCapabilities.Status
import com.electricdreams.numo.ui.util.applySettingsWindowInsets
import com.electricdreams.numo.util.overridePendingTransitionCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen answer to "why can't I charge?": what still works offline and what doesn't.
 *
 * If the connection returns while it's open, it flips to "You're back online.", ticks every row
 * green, and closes itself.
 */
class OfflineExplainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineExplainerBinding
    private val rowViews = mutableListOf<Pair<OfflineCapabilities.Row, ItemOfflineCapabilityBinding>>()
    private var celebrating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineExplainerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        binding.closeButton.setOnClickListener { finish() }
        renderRows(OfflineCapabilities.rows(OfflineCapabilities.Config.from(this)))
        observeConnectivity()
    }

    private fun observeConnectivity() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var sawOffline = false
                ConnectivityMonitor.getInstance(this@OfflineExplainerActivity).isOnline
                    .collect { online ->
                        when {
                            !online -> sawOffline = true
                            sawOffline -> celebrateAndClose()
                            // Opened while already online: nothing to explain.
                            else -> finish()
                        }
                    }
            }
        }
    }

    private fun renderRows(rows: List<OfflineCapabilities.Row>) {
        binding.capabilityList.removeAllViews()
        rowViews.clear()
        rows.forEach { row ->
            val item = ItemOfflineCapabilityBinding.inflate(
                layoutInflater, binding.capabilityList, true
            )
            item.capabilityTitle.setText(titleFor(row.feature))
            item.capabilityStatus.text = statusTextFor(row)
            item.capabilityIcon.setImageResource(iconFor(row.status))
            item.root.contentDescription =
                "${item.capabilityTitle.text}, ${item.capabilityStatus.text}"
            rowViews += row to item
        }
    }

    private fun celebrateAndClose() {
        if (celebrating) return
        celebrating = true

        swapIcon(binding.heroBadge, R.drawable.ic_status_available, startDelay = 0L)
        crossfadeText(binding.title, getString(R.string.offline_explainer_back_online_title))
        crossfadeText(binding.subtitle, getString(R.string.offline_explainer_back_online_subtitle))

        var delayMs = ROW_STAGGER_START_MS
        rowViews.filter { (row, _) -> row.status != Status.AVAILABLE }.forEach { (row, item) ->
            swapIcon(item.capabilityIcon, R.drawable.ic_status_available, startDelay = delayMs)
            crossfadeText(
                item.capabilityStatus,
                getString(R.string.offline_status_available),
                startDelay = delayMs
            )
            item.root.contentDescription =
                "${getString(titleFor(row.feature))}, ${getString(R.string.offline_status_available)}"
            delayMs += ROW_STAGGER_MS
        }

        lifecycleScope.launch {
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
                    .setInterpolator(OvershootInterpolator(2f))
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

    private fun titleFor(feature: Feature): Int = when (feature) {
        Feature.CATALOG -> R.string.offline_feature_catalog
        Feature.HISTORY -> R.string.offline_feature_history
        Feature.EXCHANGE_RATE -> R.string.offline_feature_exchange_rate
        Feature.ACCEPT_PAYMENTS -> R.string.offline_feature_accept_payments
        Feature.WITHDRAWALS -> R.string.offline_feature_withdrawals
        Feature.AUTO_WITHDRAW -> R.string.offline_feature_auto_withdraw
    }

    private fun statusTextFor(row: OfflineCapabilities.Row): String = when {
        row.status == Status.AVAILABLE -> getString(R.string.offline_status_available)
        row.updatedAt != null ->
            getString(R.string.offline_status_last_updated, formatUpdatedAt(row.updatedAt))
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
        private const val ROW_STAGGER_START_MS = 120L
        private const val ROW_STAGGER_MS = 60L
        private const val ICON_OUT_MS = 120L
        private const val ICON_IN_MS = 320L
        private const val TEXT_OUT_MS = 120L
        private const val TEXT_IN_MS = 220L
        private const val TEXT_RISE_DP = 6f
        private const val AUTO_CLOSE_MS = 1_500L

        fun start(context: Context) {
            context.startActivity(Intent(context, OfflineExplainerActivity::class.java))
        }
    }
}
