package com.electricdreams.numo.feature.insights

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.databinding.ActivityInsightsBinding
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.components.EmptyStateHelper
import com.electricdreams.numo.ui.util.TransactionTransitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class InsightsActivity : AppCompatActivity(), InsightsOptionsSheet.Host {

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
        TransactionTransitions.prepareList(this)
        super.onCreate(savedInstanceState)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        unit = DisplayUnit.fromKey(prefs().getString(KEY_UNIT, null))
        range = InsightsRange.fromKey(prefs().getString(KEY_RANGE, null))

        binding.topBar.onNavClick { finish() }
        binding.topBar.onActionClick {
            if (supportFragmentManager.findFragmentByTag(InsightsOptionsSheet.TAG) == null) {
                InsightsOptionsSheet().show(supportFragmentManager, InsightsOptionsSheet.TAG)
            }
        }

        adapter = InsightsTransactionAdapter(unit, Amount.Currency.USD, ::openTransaction)
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
            renderEmpty(d, animate)
            return
        }

        binding.transactionsRecycler.visibility = View.VISIBLE
        binding.emptyView.root.visibility = View.GONE

        val sel = selectedIndex
        if (sel == null) {
            binding.statLabel.text = getString(periodLabelRes(d.range))
            updatePrimary(d.periodTotalSats, d.periodTotalFiatMinor, d.fiatCurrency, animate)
            renderSecondary(d.periodTxCount, d.periodTipSats, d.periodTipFiatMinor, d.fiatCurrency)
            adapter.submit(d.transactions, unit, d.fiatCurrency)
        } else {
            val bucket = d.buckets[sel]
            val bucketLabel = formatSelectedBucketLabel(d.range, bucket)
            binding.statLabel.text = bucketLabel
            updatePrimary(bucket.totalSats, bucket.totalFiatMinor, d.fiatCurrency, animate)
            renderSecondary(bucket.transactionCount, bucket.tipSats, bucket.tipFiatMinor, d.fiatCurrency)

            val slice = d.transactions.filter {
                it.date.time in bucket.startMillis until bucket.endExclusiveMillis
            }
            adapter.submit(slice, unit, d.fiatCurrency)

            if (slice.isEmpty()) {
                showEmpty(getString(R.string.insights_empty_day, bucketLabel), "")
            }
        }
    }

    private fun renderEmpty(d: InsightsData, animate: Boolean) {
        binding.statLabel.text = getString(periodLabelRes(d.range))
        updatePrimary(0L, 0L, d.fiatCurrency, animate)
        binding.statSecondary.visibility = View.GONE
        adapter.submit(emptyList(), unit, d.fiatCurrency)
        showEmpty(getString(R.string.insights_empty_title), getString(R.string.insights_empty_subtitle))
    }

    private fun showEmpty(title: String, description: String) {
        binding.transactionsRecycler.visibility = View.GONE
        binding.emptyView.root.visibility = View.VISIBLE
        EmptyStateHelper.bind(binding.emptyView.root, R.drawable.ic_receipt, title, description)
    }

    /** "12 sales", then "· $4.50 in tips" when any were added. Tips never count as sales. */
    private fun renderSecondary(count: Int, tipSats: Long, tipFiatMinor: Long, fiat: Amount.Currency) {
        val sales = resources.getQuantityString(R.plurals.insights_sales_count, count, count)
        val hasTips = tipSats > 0 || tipFiatMinor > 0
        binding.statSecondary.text = if (hasTips) {
            val tips = InsightsFormatter.format(unit, tipSats, tipFiatMinor, fiat)
            getString(R.string.insights_sales_and_tips, sales, getString(R.string.insights_tips_amount, tips))
        } else {
            sales
        }
        binding.statSecondary.visibility = View.VISIBLE
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

    /** The same details screen a tap in Activity opens, grown from the tapped row */
    private fun openTransaction(row: TxRow, rowView: View) {
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                PaymentsHistoryActivity.getPaymentEntryById(this@InsightsActivity, row.id)
            }
            if (entry == null) {
                Log.w(TAG, "Sale ${row.id} is no longer in history")
                refresh(animate = true)
                return@launch
            }
            TransactionTransitions.open(
                this@InsightsActivity,
                rowView,
                PaymentIntentFactory.createTransactionDetailIntent(this@InsightsActivity, entry),
            )
        }
    }

    override val insightsUnit: DisplayUnit get() = unit

    override val insightsRange: InsightsRange get() = range

    override fun applyInsightsUnit(unit: DisplayUnit) {
        if (unit == this.unit) return
        this.unit = unit
        prefs().edit().putString(KEY_UNIT, unit.toKey()).apply()
        renderForSelection(animate = false)
    }

    override fun applyInsightsRange(range: InsightsRange) {
        if (range == this.range) return
        this.range = range
        selectedIndex = null
        prefs().edit().putString(KEY_RANGE, range.toKey()).apply()
        refresh(animate = true)
    }

    private fun periodLabelRes(range: InsightsRange): Int = when (range) {
        InsightsRange.DAY -> R.string.insights_range_days
        InsightsRange.WEEK -> R.string.insights_range_weeks
        InsightsRange.MONTH -> R.string.insights_range_months
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
        private const val TAG = "InsightsActivity"
        private const val PREFS_NAME = "InsightsPrefs"
        private const val KEY_UNIT = "display_unit"
        private const val KEY_RANGE = "date_range"
    }
}
