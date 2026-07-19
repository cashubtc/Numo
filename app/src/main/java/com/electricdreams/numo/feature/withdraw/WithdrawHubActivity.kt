package com.electricdreams.numo.feature.withdraw

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawHistoryAdapter
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsActivity
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.settings.WithdrawLightningActivity
import com.electricdreams.numo.ui.components.EmptyStateHelper
import com.electricdreams.numo.ui.components.MintSelectionBottomSheet
import com.electricdreams.numo.ui.components.NumoTopBar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Action-first hub for withdrawals: total available balance with a primary
 * Withdraw CTA on top, a compact auto-withdraw summary card linking to the
 * dedicated settings screen, and recent withdrawal activity below.
 */
class WithdrawHubActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawHub"
        private const val HISTORY_PREVIEW_COUNT = 5
    }

    private lateinit var settingsManager: AutoWithdrawSettingsManager
    private lateinit var autoWithdrawManager: AutoWithdrawManager
    private lateinit var lightningAddressManager: LightningAddressManager

    private lateinit var balanceText: TextView
    private lateinit var fiatBalanceText: TextView
    private lateinit var withdrawButton: Button
    private lateinit var zeroBalanceHint: TextView
    private lateinit var autoWithdrawCard: MaterialCardView
    private lateinit var autoStatusBadge: TextView
    private lateinit var autoSummaryText: TextView
    private lateinit var historyCard: CardView
    private lateinit var historyEmptyContainer: View
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var seeAllButton: TextView

    // Latest known per-mint balances (only mints with balance > 0)
    private var mintsWithBalance: Map<String, Long> = emptyMap()

    private val balanceRefreshReceiver: BroadcastReceiver = BalanceRefreshBroadcast.createReceiver {
        loadBalances()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_hub)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        settingsManager = AutoWithdrawSettingsManager.getInstance(this)
        autoWithdrawManager = AutoWithdrawManager.getInstance(this)
        lightningAddressManager = LightningAddressManager.getInstance(this)

        initViews()
    }

    private fun initViews() {
        findViewById<NumoTopBar>(R.id.top_bar).onNavClick {
            onBackPressedDispatcher.onBackPressed()
        }

        balanceText = findViewById(R.id.balance_text)
        fiatBalanceText = findViewById(R.id.fiat_balance_text)
        withdrawButton = findViewById(R.id.withdraw_button)
        zeroBalanceHint = findViewById(R.id.zero_balance_hint)
        autoWithdrawCard = findViewById(R.id.auto_withdraw_card)
        autoStatusBadge = findViewById(R.id.auto_status_badge)
        autoSummaryText = findViewById(R.id.auto_summary_text)
        historyCard = findViewById(R.id.history_card)
        historyEmptyContainer = findViewById(R.id.history_empty_container)
        historyRecyclerView = findViewById(R.id.history_recycler_view)
        seeAllButton = findViewById(R.id.see_all_button)

        historyRecyclerView.layoutManager = LinearLayoutManager(this)

        withdrawButton.setOnClickListener { onWithdrawClicked() }

        autoWithdrawCard.setOnClickListener {
            startActivity(Intent(this, AutoWithdrawSettingsActivity::class.java))
        }

        seeAllButton.setOnClickListener {
            startActivity(Intent(this, PaymentsHistoryActivity::class.java))
        }
    }

    /** Skip the mint sheet when only one mint holds a balance. */
    private fun onWithdrawClicked() {
        val funded = mintsWithBalance
        when {
            funded.isEmpty() -> {
                zeroBalanceHint.visibility = View.VISIBLE
            }
            funded.size == 1 -> {
                val (mintUrl, balance) = funded.entries.first()
                openWithdrawScreen(mintUrl, balance)
            }
            else -> {
                val bottomSheet = MintSelectionBottomSheet.newInstance(
                    mintBalances = funded,
                    listener = object : MintSelectionBottomSheet.OnMintSelectedListener {
                        override fun onMintSelected(mintUrl: String, balance: Long) {
                            openWithdrawScreen(mintUrl, balance)
                        }
                    }
                )
                bottomSheet.show(supportFragmentManager, "MintSelectionBottomSheet")
            }
        }
    }

    private fun openWithdrawScreen(mintUrl: String, balance: Long) {
        val intent = Intent(this, WithdrawLightningActivity::class.java).apply {
            putExtra("mint_url", mintUrl)
            putExtra("balance", balance)
        }
        startActivity(intent)
    }

    private fun loadBalances() {
        lifecycleScope.launch {
            try {
                val balances = withContext(Dispatchers.IO) {
                    CashuWalletManager.getAllMintBalances()
                }
                withContext(Dispatchers.Main) {
                    mintsWithBalance = balances.filter { it.value > 0 }
                    val total = balances.values.sum()

                    balanceText.text = Amount(total, Amount.Currency.BTC).toString()

                    val priceWorker = BitcoinPriceWorker.getInstance(this@WithdrawHubActivity)
                    val fiatAmount = priceWorker.satoshisToFiat(total)
                    if (fiatAmount > 0) {
                        fiatBalanceText.text = priceWorker.formatFiatAmount(fiatAmount)
                        fiatBalanceText.visibility = View.VISIBLE
                    } else {
                        fiatBalanceText.visibility = View.GONE
                    }

                    val hasFunds = mintsWithBalance.isNotEmpty()
                    withdrawButton.isEnabled = hasFunds
                    withdrawButton.alpha = if (hasFunds) 1.0f else 0.5f
                    if (hasFunds) {
                        zeroBalanceHint.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading balances", e)
            }
        }
    }

    private fun updateAutoWithdrawSummary() {
        val enabled = settingsManager.isGloballyEnabled()
        val address = settingsManager.getDefaultLightningAddress()
        val hasValidAddress = lightningAddressManager.isValidLightningAddress(address)

        when {
            !enabled -> {
                autoStatusBadge.text = getString(R.string.withdraw_hub_status_off)
                autoStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_text_tertiary))
                autoStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_badge)
                autoSummaryText.text = getString(R.string.withdraw_hub_auto_off_summary)
            }
            !hasValidAddress -> {
                autoStatusBadge.text = getString(R.string.withdraw_hub_status_needs_setup)
                autoStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_warning))
                autoStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_status_pill_pending)
                autoSummaryText.text = getString(R.string.withdraw_hub_auto_needs_setup_summary)
            }
            else -> {
                autoStatusBadge.text = getString(R.string.auto_withdraw_status_active)
                autoStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))
                autoStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_status_pill_success)
                val threshold = Amount(settingsManager.getDefaultThreshold(), Amount.Currency.BTC)
                autoSummaryText.text = getString(R.string.withdraw_hub_auto_summary, address, threshold.toString())
            }
        }
    }

    private fun loadHistory() {
        val history = autoWithdrawManager.getHistory()

        if (history.isEmpty()) {
            historyEmptyContainer.visibility = View.VISIBLE
            EmptyStateHelper.bind(
                historyEmptyContainer,
                R.drawable.ic_history,
                getString(R.string.withdraw_history_empty_title),
                getString(R.string.withdraw_history_empty_subtitle)
            )
            historyRecyclerView.visibility = View.GONE
            seeAllButton.visibility = View.GONE
        } else {
            historyEmptyContainer.visibility = View.GONE
            historyRecyclerView.visibility = View.VISIBLE
            seeAllButton.visibility = if (history.size > HISTORY_PREVIEW_COUNT) View.VISIBLE else View.GONE
            historyRecyclerView.adapter = AutoWithdrawHistoryAdapter(history.take(HISTORY_PREVIEW_COUNT))
        }
    }

    override fun onStart() {
        super.onStart()
        BalanceRefreshBroadcast.register(this, balanceRefreshReceiver)
    }

    override fun onStop() {
        super.onStop()
        BalanceRefreshBroadcast.unregister(this, balanceRefreshReceiver)
    }

    override fun onResume() {
        super.onResume()
        loadBalances()
        updateAutoWithdrawSummary()
        loadHistory()
    }
}
