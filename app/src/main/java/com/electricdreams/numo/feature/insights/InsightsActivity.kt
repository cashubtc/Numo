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
import java.util.Locale

class InsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInsightsBinding
    private lateinit var adapter: InsightsTransactionAdapter

    private var unit: DisplayUnit = DisplayUnit.FIAT
    private var data: InsightsData? = null
    private var selectedDayIndex: Int? = null

    private var balanceReceiver: BroadcastReceiver? = null

    private var primaryAnimator: ValueAnimator? = null
    private var secondaryAnimator: ValueAnimator? = null
    private var lastPrimarySats: Long = 0L
    private var lastPrimaryFiatMinor: Long = 0L
    private var lastSecondarySats: Long = 0L
    private var lastSecondaryFiatMinor: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        unit = DisplayUnit.fromKey(prefs().getString(KEY_UNIT, null))

        binding.backButton.setOnClickListener { finish() }
        binding.viewOptionsButton.setOnClickListener { openViewOptions() }

        adapter = InsightsTransactionAdapter(unit, Amount.Currency.USD)
        binding.transactionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.transactionsRecycler.adapter = adapter

        binding.barChart.setOnSelectionChanged { idx ->
            selectedDayIndex = idx
            renderForSelection(animate = true)
        }

        binding.statLabel.text = getString(R.string.insights_this_week)
        binding.statSecondaryLabel.text = getString(R.string.insights_avg_daily)

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
                InsightsRepository.computeLast7Days(this@InsightsActivity)
            }
            data = result
            binding.barChart.setData(result.perDay)
            binding.barChart.setSelectedDay(selectedDayIndex)
            renderForSelection(animate)
        }
    }

    private fun renderForSelection(animate: Boolean) {
        val d = data ?: return
        val isEmptyPeriod = d.periodTxCount == 0

        if (isEmptyPeriod && selectedDayIndex == null) {
            renderEmpty(d)
            return
        }

        binding.statPair.visibility = View.VISIBLE
        binding.transactionsRecycler.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        val sel = selectedDayIndex
        if (sel == null) {
            binding.statLabel.text = getString(R.string.insights_this_week)
            updatePrimary(d.periodTotalSats, d.periodTotalFiatMinor, d.fiatCurrency, animate)
            binding.statSecondary.visibility = View.VISIBLE
            binding.statSecondaryLabel.visibility = View.VISIBLE
            binding.statSecondaryLabel.text = getString(R.string.insights_avg_daily)
            updateSecondary(d.avgDailySats, d.avgDailyFiatMinor, d.fiatCurrency, animate)
            adapter.submit(d.transactions, unit, d.fiatCurrency)
        } else {
            val day = d.perDay[sel]
            val dayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(day.date)
            binding.statLabel.text = dayName
            updatePrimary(day.totalSats, day.totalFiatMinor, d.fiatCurrency, animate)
            binding.statSecondary.visibility = View.VISIBLE
            binding.statSecondaryLabel.visibility = View.GONE
            secondaryAnimator?.cancel()
            binding.statSecondaryValue.text = if (day.transactionCount == 1) {
                getString(R.string.insights_day_count_one)
            } else {
                getString(R.string.insights_day_count_other, day.transactionCount)
            }

            val daySlice = d.transactions.filter {
                it.date.time in day.dayStartMillis until day.dayEndExclusiveMillis
            }
            adapter.submit(daySlice, unit, d.fiatCurrency)

            if (daySlice.isEmpty()) {
                binding.transactionsRecycler.visibility = View.GONE
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text = getString(R.string.insights_empty_day, dayName)
            }
        }
    }

    private fun renderEmpty(d: InsightsData) {
        binding.statPair.visibility = View.VISIBLE
        binding.transactionsRecycler.visibility = View.GONE
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = getString(R.string.insights_empty_hint)

        binding.statLabel.text = getString(R.string.insights_this_week)
        binding.statValue.text = getString(R.string.insights_empty_headline)
        binding.statSecondary.visibility = View.GONE
        primaryAnimator?.cancel()
        secondaryAnimator?.cancel()
        lastPrimarySats = 0L
        lastPrimaryFiatMinor = 0L
        lastSecondarySats = 0L
        lastSecondaryFiatMinor = 0L
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

    private fun updateSecondary(sats: Long, fiatMinor: Long, fiat: Amount.Currency, animate: Boolean) {
        if (animate && (sats != lastSecondarySats || fiatMinor != lastSecondaryFiatMinor)) {
            secondaryAnimator?.cancel()
            secondaryAnimator = animatePair(lastSecondarySats, sats, lastSecondaryFiatMinor, fiatMinor) { s, f ->
                binding.statSecondaryValue.text = InsightsFormatter.format(unit, s, f, fiat)
            }
        } else {
            secondaryAnimator?.cancel()
            binding.statSecondaryValue.text = InsightsFormatter.format(unit, sats, fiatMinor, fiat)
        }
        lastSecondarySats = sats
        lastSecondaryFiatMinor = fiatMinor
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
            configure(unit) { newUnit ->
                if (newUnit == unit) return@configure
                unit = newUnit
                prefs().edit().putString(KEY_UNIT, newUnit.toKey()).apply()
                renderForSelection(animate = false)
            }
        }.show(supportFragmentManager, ViewOptionsSheet.TAG)
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "InsightsPrefs"
        private const val KEY_UNIT = "display_unit"
    }
}
