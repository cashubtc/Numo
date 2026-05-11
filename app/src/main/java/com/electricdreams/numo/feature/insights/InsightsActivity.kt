package com.electricdreams.numo.feature.insights

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.databinding.ActivityInsightsBinding
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class InsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInsightsBinding
    private lateinit var adapter: InsightsTransactionAdapter

    private var unit: DisplayUnit = DisplayUnit.FIAT
    private var range: InsightsRange = InsightsRange.DAY
    private var data: InsightsData? = null
    private var selectedIndex: Int? = null

    private var balanceReceiver: BroadcastReceiver? = null

    private var primaryAnimator: ValueAnimator? = null
    private var lastPrimarySats: Long = 0L
    private var lastPrimaryFiatMinor: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        unit = DisplayUnit.fromKey(prefs().getString(KEY_UNIT, null))
        range = InsightsRange.fromKey(prefs().getString(KEY_RANGE, null))

        binding.backButton.setOnClickListener { finish() }
        binding.viewOptionsButton.setOnClickListener { openViewOptions() }

        adapter = InsightsTransactionAdapter(unit, Amount.Currency.USD)
        binding.transactionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.transactionsRecycler.adapter = adapter

        binding.barChart.setOnSelectionChanged { idx ->
            selectedIndex = idx
            renderForSelection(animate = true)
        }

        binding.statLabel.text = getString(periodLabelRes(range))

        refresh(animate = false)
    }

    override fun onResume() {
        super.onResume()
        balanceReceiver = BalanceRefreshBroadcast.createReceiver { refresh(animate = true) }
        BalanceRefreshBroadcast.register(this, balanceReceiver!!)
        refresh(animate = false)
    }

    override fun onPause() {
        super.onPause()
        balanceReceiver?.let {
            BalanceRefreshBroadcast.unregister(this, it)
            balanceReceiver = null
        }
    }

    private fun refresh(animate: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                InsightsRepository.compute(this@InsightsActivity, range)
            }
            data = result
            binding.barChart.setData(result.buckets)
            binding.barChart.setSelectedIndex(selectedIndex)
            renderForSelection(animate)
        }
    }

    private fun renderForSelection(animate: Boolean) {
        val d = data ?: return
        val isEmptyPeriod = d.periodTxCount == 0

        if (isEmptyPeriod && selectedIndex == null) {
            renderEmpty(d)
            return
        }

        binding.statPair.visibility = View.VISIBLE
        binding.transactionsRecycler.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        val sel = selectedIndex
        if (sel == null) {
            binding.statLabel.text = getString(periodLabelRes(d.range))
            updatePrimary(d.periodTotalSats, d.periodTotalFiatMinor, d.fiatCurrency, animate)
            binding.statSecondary.visibility = View.GONE
            adapter.submit(d.transactions, unit, d.fiatCurrency)
        } else {
            val bucket = d.buckets[sel]
            val bucketLabel = formatSelectedBucketLabel(d.range, bucket)
            binding.statLabel.text = bucketLabel
            updatePrimary(bucket.totalSats, bucket.totalFiatMinor, d.fiatCurrency, animate)
            binding.statSecondary.visibility = View.VISIBLE
            binding.statSecondaryLabel.visibility = View.GONE
            binding.statSecondaryValue.text = if (bucket.transactionCount == 1) {
                getString(R.string.insights_day_count_one)
            } else {
                getString(R.string.insights_day_count_other, bucket.transactionCount)
            }

            val slice = d.transactions.filter {
                it.date.time in bucket.startMillis until bucket.endExclusiveMillis
            }
            adapter.submit(slice, unit, d.fiatCurrency)

            if (slice.isEmpty()) {
                binding.transactionsRecycler.visibility = View.GONE
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text = getString(R.string.insights_empty_day, bucketLabel)
            }
        }
    }

    private fun renderEmpty(d: InsightsData) {
        binding.statPair.visibility = View.VISIBLE
        binding.transactionsRecycler.visibility = View.GONE
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = getString(R.string.insights_empty_hint)

        binding.statLabel.text = getString(periodLabelRes(d.range))
        binding.statValue.text = getString(R.string.insights_empty_headline)
        binding.statSecondary.visibility = View.GONE
        primaryAnimator?.cancel()
        lastPrimarySats = 0L
        lastPrimaryFiatMinor = 0L
    }

    private fun updatePrimary(sats: Long, fiatMinor: Long, fiat: Amount.Currency, animate: Boolean) {
        if (animate && (sats != lastPrimarySats || fiatMinor != lastPrimaryFiatMinor)) {
            primaryAnimator?.cancel()
            primaryAnimator = animatePair(lastPrimarySats, sats, lastPrimaryFiatMinor, fiatMinor) { s, f ->
                binding.statValue.text = InsightsFormatter.format(unit, s, f, fiat)
            }
        } else {
            primaryAnimator?.cancel()
            binding.statValue.text = InsightsFormatter.format(unit, sats, fiatMinor, fiat)
        }
        lastPrimarySats = sats
        lastPrimaryFiatMinor = fiatMinor
    }

    private fun animatePair(
        fromSats: Long, toSats: Long,
        fromFiat: Long, toFiat: Long,
        onUpdate: (Long, Long) -> Unit,
    ): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 350L
        interpolator = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
        addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            val s = (fromSats + (toSats - fromSats) * t).toLong()
            val f = (fromFiat + (toFiat - fromFiat) * t).toLong()
            onUpdate(s, f)
        }
        start()
    }

    private fun openViewOptions() {
        ViewOptionsSheet().apply {
            configure(
                currentUnit = unit,
                currentRange = range,
                onUnitChanged = { newUnit ->
                    if (newUnit == unit) return@configure
                    unit = newUnit
                    prefs().edit().putString(KEY_UNIT, newUnit.toKey()).apply()
                    renderForSelection(animate = false)
                },
                onRangeChanged = { newRange ->
                    if (newRange == range) return@configure
                    range = newRange
                    selectedIndex = null
                    prefs().edit().putString(KEY_RANGE, newRange.toKey()).apply()
                    refresh(animate = true)
                },
            )
        }.show(supportFragmentManager, ViewOptionsSheet.TAG)
    }

    private fun periodLabelRes(range: InsightsRange): Int = when (range) {
        InsightsRange.DAY -> R.string.insights_this_week
        InsightsRange.WEEK -> R.string.insights_last_7_weeks
        InsightsRange.MONTH -> R.string.insights_last_7_months
    }

    private fun formatSelectedBucketLabel(range: InsightsRange, bucket: BucketTotal): String {
        val locale = Locale.getDefault()
        val start = Date(bucket.startMillis)
        return when (range) {
            InsightsRange.DAY -> SimpleDateFormat("EEEE", locale).format(start)
            InsightsRange.WEEK -> {
                val endInclusive = Date(bucket.endExclusiveMillis - 1)
                val startCal = Calendar.getInstance(locale).apply { time = start }
                val endCal = Calendar.getInstance(locale).apply { time = endInclusive }
                val mmmD = SimpleDateFormat("MMM d", locale)
                if (startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH)) {
                    val dayOnly = SimpleDateFormat("d", locale)
                    "${mmmD.format(start)} – ${dayOnly.format(endInclusive)}"
                } else {
                    "${mmmD.format(start)} – ${mmmD.format(endInclusive)}"
                }
            }
            InsightsRange.MONTH -> {
                val startCal = Calendar.getInstance(locale).apply { time = start }
                val nowYear = Calendar.getInstance(locale).get(Calendar.YEAR)
                if (startCal.get(Calendar.YEAR) == nowYear) {
                    SimpleDateFormat("MMMM", locale).format(start)
                } else {
                    SimpleDateFormat("MMMM yyyy", locale).format(start)
                }
            }
        }
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "InsightsPrefs"
        private const val KEY_UNIT = "display_unit"
        private const val KEY_RANGE = "date_range"
    }
}
