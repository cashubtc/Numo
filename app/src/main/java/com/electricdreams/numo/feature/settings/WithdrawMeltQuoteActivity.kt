package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.FinalizedMelt
import org.cashudevkit.MintUrl
import org.cashudevkit.QuoteState

import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ActivityWithdrawMeltQuoteBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Review: the amount leads, the route and fee are spelled out, and the button says
 * exactly what will happen. Sending, failure and pending all resolve on this screen.
 */
class WithdrawMeltQuoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawMeltQuoteBinding
    private lateinit var mintUrl: String
    private lateinit var quoteId: String
    private var amount: Long = 0
    private var feeReserve: Long = 0
    private var invoice: String? = null
    private var lightningAddress: String? = null
    private var request: String = ""

    private var historyEntryId: String? = null
    private var isSending = false
    private var isPending = false

    private val sendingBackGuard = object : OnBackPressedCallback(false) {
        // A melt in flight cannot be cancelled; leaving would hide its outcome.
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWithdrawMeltQuoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        mintUrl = intent.getStringExtra(EXTRA_MINT_URL).orEmpty()
        quoteId = intent.getStringExtra(EXTRA_QUOTE_ID).orEmpty()
        amount = intent.getLongExtra(EXTRA_AMOUNT, 0)
        feeReserve = intent.getLongExtra(EXTRA_FEE_RESERVE, 0)
        invoice = intent.getStringExtra(EXTRA_INVOICE)
        lightningAddress = intent.getStringExtra(EXTRA_LIGHTNING_ADDRESS)
        request = intent.getStringExtra(EXTRA_REQUEST).orEmpty()

        if (mintUrl.isEmpty() || quoteId.isEmpty()) {
            Log.e(TAG, "Missing melt quote data")
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, sendingBackGuard)
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.sendButton.setOnClickListener { if (isPending) openHub() else send() }
        binding.secondaryButton.setOnClickListener { finish() }
        bindQuote()
    }

    private fun bindQuote() {
        binding.amountText.text = WithdrawUi.sats(amount)

        val priceWorker = BitcoinPriceWorker.getInstance(this)
        val fiat = priceWorker.satoshisToFiat(amount)
        if (fiat > 0) {
            binding.fiatText.text = priceWorker.formatFiatAmount(fiat)
        } else {
            binding.fiatText.visibility = View.GONE
        }

        val address = lightningAddress
        if (!address.isNullOrBlank()) {
            binding.destinationText.text = getString(R.string.withdraw_review_to, address)
        } else {
            binding.destinationText.text = getString(R.string.withdraw_review_to_invoice)
            binding.invoiceRow.visibility = View.VISIBLE
            binding.invoiceValue.text = invoice ?: request
        }

        binding.fromValue.text = MintManager.getInstance(this).getMintDisplayName(mintUrl)
        binding.feeValue.text = if (feeReserve > 0) {
            getString(R.string.withdraw_row_fee_value, WithdrawUi.sats(feeReserve))
        } else {
            WithdrawUi.sats(0)
        }
        binding.totalValue.text = WithdrawUi.sats(amount + feeReserve)
        binding.sendButton.text = sendLabel()
    }

    private fun sendLabel() = getString(R.string.withdraw_send_amount, WithdrawUi.sats(amount))

    private fun destinationLabel(): String =
        lightningAddress?.takeIf { it.isNotBlank() } ?: invoice ?: request

    private fun send() {
        if (isSending) return
        setSending(true)
        hideBanner()

        lifecycleScope.launch {
            val manager = AutoWithdrawManager.getInstance(this@WithdrawMeltQuoteActivity)
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    setSending(false)
                    WithdrawUi.snackbar(binding.sendButton, getString(R.string.withdraw_error_wallet))
                    return@launch
                }

                val existingId = historyEntryId
                if (existingId == null) {
                    historyEntryId = manager.addManualWithdrawalEntry(
                        mintUrl = mintUrl,
                        amountSats = amount,
                        feeSats = feeReserve,
                        destination = destinationLabel(),
                        destinationType = if (lightningAddress.isNullOrBlank()) "manual_invoice" else "manual_address",
                        status = WithdrawHistoryEntry.STATUS_PENDING,
                        quoteId = quoteId
                    ).id
                } else {
                    manager.updateWithdrawalStatus(existingId, WithdrawHistoryEntry.STATUS_PENDING)
                }

                val unit = MintManager.getInstance(this@WithdrawMeltQuoteActivity).getPreferredUnit()
                val mintWallet = wallet.getWallet(MintUrl(mintUrl), CashuWalletManager.getCurrencyUnit(unit))
                    ?: throw IllegalStateException("No wallet for mint $mintUrl")
                val finalized: FinalizedMelt = withContext(Dispatchers.IO) {
                    mintWallet.prepareMelt(quoteId).confirm()
                }
                Log.d(TAG, "Melt finished: state=${finalized.state}, feePaid=${finalized.feePaid.value}")
                onMeltFinished(manager, finalized)
            } catch (e: Exception) {
                Log.e(TAG, "Melt failed", e)
                historyEntryId?.let {
                    manager.updateWithdrawalStatus(it, WithdrawHistoryEntry.STATUS_FAILED, errorMessage = e.message)
                }
                showFailure()
            }
        }
    }

    private fun onMeltFinished(manager: AutoWithdrawManager, finalized: FinalizedMelt) {
        val entryId = historyEntryId
        when (finalized.state) {
            QuoteState.PAID -> {
                val feePaid = finalized.feePaid.value.toLong()
                entryId?.let {
                    manager.updateWithdrawalStatus(it, WithdrawHistoryEntry.STATUS_COMPLETED, feeSats = feePaid)
                }
                WalletLogger.log("OUT", amount, mintUrl, "Withdrawal successful: ${destinationLabel()}")
                startActivity(
                    Intent(this, WithdrawSuccessActivity::class.java)
                        .putExtra(WithdrawSuccessActivity.EXTRA_AMOUNT, amount)
                        .putExtra(WithdrawSuccessActivity.EXTRA_FEE_PAID, feePaid)
                        .putExtra(WithdrawSuccessActivity.EXTRA_LIGHTNING_ADDRESS, lightningAddress)
                )
                finish()
            }
            QuoteState.PENDING -> showPending()
            else -> {
                entryId?.let {
                    manager.updateWithdrawalStatus(
                        it,
                        WithdrawHistoryEntry.STATUS_FAILED,
                        errorMessage = getString(R.string.withdraw_melt_error_invoice_not_paid)
                    )
                }
                showFailure()
            }
        }
    }

    private fun showFailure() {
        setSending(false)
        showBanner(
            backgroundRes = R.color.color_status_error_bg,
            iconRes = R.drawable.ic_warning,
            iconTintRes = R.color.m3_error,
            title = getString(R.string.withdraw_failed_title),
            body = getString(R.string.withdraw_failed_body)
        )
        binding.sendButton.text = getString(R.string.withdraw_try_again)
        binding.secondaryButton.visibility = View.VISIBLE
    }

    private fun showPending() {
        isPending = true
        setSending(false)
        showBanner(
            backgroundRes = R.color.color_status_pending_bg,
            iconRes = R.drawable.ic_pending,
            iconTintRes = R.color.color_text_primary,
            title = getString(R.string.withdraw_pending_title),
            body = getString(R.string.withdraw_pending_body)
        )
        binding.sendButton.text = getString(R.string.withdraw_done)
        binding.secondaryButton.visibility = View.GONE
    }

    private fun showBanner(
        @ColorRes backgroundRes: Int,
        @DrawableRes iconRes: Int,
        @ColorRes iconTintRes: Int,
        title: String,
        body: String,
    ) {
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup, AutoTransition())
        binding.statusBanner.backgroundTintList = ContextCompat.getColorStateList(this, backgroundRes)
        binding.statusIcon.setImageResource(iconRes)
        binding.statusIcon.imageTintList = ContextCompat.getColorStateList(this, iconTintRes)
        binding.statusTitle.text = title
        binding.statusBody.text = body
        binding.statusBanner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        if (binding.statusBanner.visibility != View.VISIBLE) return
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup, AutoTransition())
        binding.statusBanner.visibility = View.GONE
        binding.secondaryButton.visibility = View.GONE
    }

    private fun setSending(sending: Boolean) {
        isSending = sending
        sendingBackGuard.isEnabled = sending
        binding.topBar.setNavEnabled(!sending)
        WithdrawUi.setButtonBusy(
            binding.sendButton,
            sending,
            if (sending) getString(R.string.withdraw_sending) else sendLabel()
        )
        if (sending) {
            binding.sendButton.announceForAccessibility(
                getString(R.string.withdraw_sending_status, WithdrawUi.sats(amount))
            )
        }
    }

    private fun openHub() {
        startActivity(
            Intent(this, WithdrawActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val TAG = "WithdrawMeltQuote"
        const val EXTRA_MINT_URL = "mint_url"
        const val EXTRA_QUOTE_ID = "quote_id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_FEE_RESERVE = "fee_reserve"
        const val EXTRA_INVOICE = "invoice"
        const val EXTRA_LIGHTNING_ADDRESS = "lightning_address"
        const val EXTRA_REQUEST = "request"
    }
}
