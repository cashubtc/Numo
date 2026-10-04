package com.electricdreams.numo.feature.settings

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ActivityWithdrawBinding
import com.electricdreams.numo.databinding.ItemWithdrawRecentBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsActivity
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawSettingsManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Withdraw hub: what is ready to leave the terminal, one action to send it to the
 * merchant's wallet, the auto-withdraw rule, and the most recent withdrawals.
 */
class WithdrawActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawBinding
    private var hasLoaded = false
    private var openedDetailEntryId: String? = null

    private val detailLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val entryId = openedDetailEntryId
            openedDetailEntryId = null
            val deleteRequested = result.data?.hasExtra(EXTRA_POSITION_TO_DELETE) == true
            if (result.resultCode == RESULT_OK && deleteRequested && entryId != null) {
                AutoWithdrawManager.getInstance(this).deleteHistoryEntry(entryId)
                bindRecent()
            }
        }

    private val balanceRefreshReceiver: BroadcastReceiver =
        BalanceRefreshBroadcast.createReceiver { reason ->
            Log.d(TAG, "Balance refresh broadcast received: $reason")
            loadBalance()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWithdrawBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        binding.topBar.onNavClick { finish() }
        binding.sendButton.setOnClickListener {
            startActivity(Intent(this, WithdrawLightningActivity::class.java))
        }
        binding.exportButton.setOnClickListener {
            startActivity(Intent(this, WithdrawEcashActivity::class.java))
        }
        binding.autoWithdrawRow.setOnClickListener {
            startActivity(Intent(this, AutoWithdrawSettingsActivity::class.java))
        }
        binding.seeAllRow.setOnClickListener {
            startActivity(Intent(this, PaymentsHistoryActivity::class.java))
        }

        // Hold the headline until the first balance arrives, then fade it in once,
        // so the screen never re-flows in front of the merchant.
        setHeadlineAlpha(0f)
        setActionsEnabled(false)
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
        loadBalance()
        bindAutoWithdraw()
        bindRecent()
    }

    private fun loadBalance() {
        lifecycleScope.launch {
            val total = try {
                withContext(Dispatchers.IO) { CashuWalletManager.getAllMintBalances().values.sum() }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to load balance", e)
                0L
            }
            bindBalance(total)
            if (!hasLoaded) {
                hasLoaded = true
                binding.balanceText.animate().alpha(1f).setDuration(FADE_MS).start()
                binding.fiatText.animate().alpha(1f).setDuration(FADE_MS).start()
                binding.destinationText.animate().alpha(1f).setDuration(FADE_MS).start()
            }
        }
    }

    private fun bindBalance(total: Long) {
        binding.balanceText.text = WithdrawUi.sats(total)

        val priceWorker = BitcoinPriceWorker.getInstance(this)
        val fiat = priceWorker.satoshisToFiat(total)
        // INVISIBLE rather than GONE keeps the headline block a constant height.
        if (fiat > 0) {
            binding.fiatText.text = priceWorker.formatFiatAmount(fiat)
            binding.fiatText.visibility = View.VISIBLE
        } else {
            binding.fiatText.visibility = View.INVISIBLE
        }

        val address = LightningAddressManager.getInstance(this).getLightningAddress()
        binding.destinationText.text = when {
            total <= 0 -> getString(R.string.withdraw_hub_empty)
            address.isNotBlank() -> getString(R.string.withdraw_hub_ready_to_address, address)
            else -> getString(R.string.withdraw_hub_ready_to_wallet)
        }
        setActionsEnabled(total > 0)
    }

    private fun bindAutoWithdraw() {
        val settings = AutoWithdrawSettingsManager.getInstance(this)
        val subtitle = if (settings.isGloballyEnabled()) {
            getString(
                R.string.withdraw_hub_auto_on,
                settings.getDefaultPercentage(),
                WithdrawUi.sats(settings.getDefaultThreshold())
            )
        } else {
            getString(R.string.withdraw_hub_auto_off)
        }
        binding.autoWithdrawRow.setSubtitle(subtitle)
    }

    private fun bindRecent() {
        val history = AutoWithdrawManager.getInstance(this).getHistory()
        binding.recentList.removeAllViews()
        history.take(RECENT_LIMIT).forEach { entry ->
            val row = ItemWithdrawRecentBinding.inflate(layoutInflater, binding.recentList, true)
            bindRecentRow(row, entry)
        }
        binding.recentEmptyText.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        binding.seeAllRow.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindRecentRow(row: ItemWithdrawRecentBinding, entry: WithdrawHistoryEntry) {
        val destination = entry.destination.ifBlank { entry.lightningAddress.orEmpty() }
        row.recentTitle.text = when {
            entry.token != null -> getString(R.string.withdraw_recent_token)
            entry.destinationType == "manual_invoice" ||
                (destination.startsWith("ln", ignoreCase = true) && !destination.contains('@')) ->
                getString(R.string.withdraw_review_to_invoice)
            entry.automatic -> getString(R.string.withdraw_recent_auto_to, destination)
            else -> getString(R.string.withdraw_recent_to, destination)
        }
        row.recentDate.text = DateUtils.formatDateTime(
            this,
            entry.timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )
        row.recentAmount.text = WithdrawUi.sats(entry.amountSats)

        when (entry.status) {
            WithdrawHistoryEntry.STATUS_PENDING -> {
                row.recentIcon.setImageResource(R.drawable.ic_pending)
                row.recentIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.color_text_primary)
                row.recentStatus.visibility = View.VISIBLE
                row.recentStatus.text = getString(R.string.auto_withdraw_status_pending)
                row.recentStatus.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
            }
            WithdrawHistoryEntry.STATUS_FAILED -> {
                row.recentIcon.setImageResource(R.drawable.ic_close)
                row.recentIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.m3_error)
                row.recentStatus.visibility = View.VISIBLE
                row.recentStatus.text = getString(R.string.auto_withdraw_status_failed)
                row.recentStatus.setTextColor(ContextCompat.getColor(this, R.color.m3_error))
            }
            else -> {
                row.recentIcon.setImageResource(R.drawable.ic_arrow_up_send)
                row.recentStatus.visibility = View.GONE
            }
        }

        row.root.setOnClickListener {
            openedDetailEntryId = entry.id
            detailLauncher.launch(PaymentIntentFactory.createTransactionDetailIntent(this, entry, -1))
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.sendButton.isEnabled = enabled
        binding.exportButton.isEnabled = enabled
    }

    private fun setHeadlineAlpha(alpha: Float) {
        binding.balanceText.alpha = alpha
        binding.fiatText.alpha = alpha
        binding.destinationText.alpha = alpha
    }

    companion object {
        private const val TAG = "WithdrawActivity"
        private const val RECENT_LIMIT = 3
        private const val FADE_MS = 150L
        private const val EXTRA_POSITION_TO_DELETE = "position_to_delete"
    }
}
