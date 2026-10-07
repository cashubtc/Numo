package com.electricdreams.numo.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cashudevkit.MeltQuote
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod
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
 * exactly what will happen. Sending and every outcome resolve on this screen, and an
 * outcome that might have moved money never offers to send again.
 */
class WithdrawMeltQuoteActivity : AppCompatActivity() {

    private enum class State { READY, SENDING, REFUSED, PENDING, UNCONFIRMED }

    private sealed class MeltResult {
        data class Paid(val feePaid: Long) : MeltResult()
        object Pending : MeltResult()
        object Refused : MeltResult()
        object Unconfirmed : MeltResult()
        object WalletUnavailable : MeltResult()
    }

    private lateinit var binding: ActivityWithdrawMeltQuoteBinding
    private lateinit var mintUrl: String
    private lateinit var quoteId: String
    private var amount: Long = 0
    private var feeReserve: Long = 0
    private var invoice: String? = null
    private var lightningAddress: String? = null
    private var request: String = ""

    private var historyEntryId: String? = null
    private var state = State.READY

    private val backHandler = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // While a melt is in flight its outcome must stay on screen; once it may have
            // moved money, leaving goes to the hub rather than back to an editable form.
            if (state != State.SENDING) openHub()
        }
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

        if (savedInstanceState != null) {
            quoteId = savedInstanceState.getString(STATE_QUOTE_ID, quoteId)
            feeReserve = savedInstanceState.getLong(STATE_FEE_RESERVE, feeReserve)
            historyEntryId = savedInstanceState.getString(STATE_HISTORY_ID)
            val saved = State.valueOf(savedInstanceState.getString(STATE_NAME, State.READY.name))
            // Recreated mid-send: the melt finishes in the background, but this screen
            // can no longer know how, so it must not offer to send again.
            state = if (saved == State.SENDING) State.UNCONFIRMED else saved
        }

        onBackPressedDispatcher.addCallback(this, backHandler)
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.sendButton.setOnClickListener { onPrimaryAction() }
        binding.secondaryButton.setOnClickListener { finish() }
        bindQuote()
        render(animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUOTE_ID, quoteId)
        outState.putLong(STATE_FEE_RESERVE, feeReserve)
        outState.putString(STATE_HISTORY_ID, historyEntryId)
        outState.putString(STATE_NAME, state.name)
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
        // The mint reserves the fee up front and refunds what routing didn't use, so
        // both the fee and the total are upper bounds.
        if (feeReserve > 0) {
            binding.feeValue.text = getString(R.string.withdraw_row_fee_value, WithdrawUi.sats(feeReserve))
            binding.totalValue.text = getString(R.string.withdraw_row_fee_value, WithdrawUi.sats(amount + feeReserve))
        } else {
            binding.feeValue.text = WithdrawUi.sats(0)
            binding.totalValue.text = WithdrawUi.sats(amount)
        }
    }

    private fun onPrimaryAction() {
        when (state) {
            State.READY -> melt()
            State.REFUSED -> retryWithFreshQuote()
            State.PENDING, State.UNCONFIRMED -> openHub()
            State.SENDING -> Unit
        }
    }

    private fun melt() {
        setState(State.SENDING)
        val entryId = ensureHistoryEntry()
        val context = applicationContext
        val mint = mintUrl
        val quote = quoteId
        val sats = amount
        val destination = destinationLabel()
        // The melt runs in a process-wide scope: once started it always finishes and
        // records its outcome, even if this screen goes away.
        val running = meltScope.async { runMelt(context, mint, quote, sats, destination, entryId) }
        lifecycleScope.launch {
            val result = withTimeoutOrNull(UI_WAIT_MS) { running.await() }
            if (result != null) {
                onMeltResult(result)
                return@launch
            }
            // Lightning can take minutes to settle. Free the merchant now, and keep
            // listening while this screen is open in case it resolves.
            setState(State.PENDING)
            onMeltResult(running.await())
        }
    }

    private fun ensureHistoryEntry(): String {
        val manager = AutoWithdrawManager.getInstance(applicationContext)
        historyEntryId?.let {
            manager.updateWithdrawalStatus(it, WithdrawHistoryEntry.STATUS_PENDING)
            return it
        }
        return manager.addManualWithdrawalEntry(
            mintUrl = mintUrl,
            amountSats = amount,
            feeSats = feeReserve,
            destination = destinationLabel(),
            destinationType = if (lightningAddress.isNullOrBlank()) "manual_invoice" else "manual_address",
            status = WithdrawHistoryEntry.STATUS_PENDING,
            quoteId = quoteId
        ).id.also { historyEntryId = it }
    }

    private fun onMeltResult(result: MeltResult) {
        when (result) {
            is MeltResult.Paid -> {
                startActivity(
                    Intent(this, WithdrawSuccessActivity::class.java)
                        .putExtra(WithdrawSuccessActivity.EXTRA_AMOUNT, amount)
                        .putExtra(WithdrawSuccessActivity.EXTRA_FEE_PAID, result.feePaid)
                        .putExtra(WithdrawSuccessActivity.EXTRA_LIGHTNING_ADDRESS, lightningAddress)
                )
                finish()
            }
            MeltResult.Pending -> setState(State.PENDING)
            MeltResult.Refused -> setState(State.REFUSED)
            MeltResult.Unconfirmed -> setState(State.UNCONFIRMED)
            MeltResult.WalletUnavailable -> {
                setState(State.READY)
                WithdrawUi.snackbar(binding.sendButton, getString(R.string.withdraw_error_wallet))
            }
        }
    }

    /**
     * A refused quote may be spent or expired, so trying again asks the mint for a new
     * one. If routing now costs more, the merchant confirms the new total first.
     */
    private fun retryWithFreshQuote() {
        setState(State.SENDING)
        lifecycleScope.launch {
            val quote = try {
                fetchQuote()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to refresh melt quote", e)
                null
            }
            if (quote == null) {
                setState(State.REFUSED)
                WithdrawUi.snackbar(binding.sendButton, getString(R.string.withdraw_error_quote))
                return@launch
            }
            val newFee = quote.feeReserve.value.toLong()
            val feeIncreased = newFee > feeReserve
            quoteId = quote.id
            feeReserve = newFee
            bindQuote()
            if (feeIncreased) {
                setState(State.READY)
                WithdrawUi.snackbar(binding.sendButton, getString(R.string.withdraw_fee_changed))
            } else {
                melt()
            }
        }
    }

    private suspend fun fetchQuote(): MeltQuote = withContext(Dispatchers.IO) {
        val wallet = CashuWalletManager.getWallet() ?: throw IllegalStateException("Wallet not initialized")
        val unit = MintManager.getInstance(applicationContext).getPreferredUnit()
        val mintWallet = wallet.getWallet(MintUrl(mintUrl), CashuWalletManager.getCurrencyUnit(unit))
            ?: throw IllegalStateException("No wallet for mint $mintUrl")
        val address = lightningAddress
        if (!address.isNullOrBlank()) {
            mintWallet.meltLightningAddressQuote(address, org.cashudevkit.Amount((amount * 1000).toULong()))
        } else {
            mintWallet.meltQuote(PaymentMethod.Bolt11, invoice ?: request, null, null)
        }
    }

    private fun setState(newState: State) {
        state = newState
        render(animate = true)
    }

    private fun render(animate: Boolean) {
        val sending = state == State.SENDING
        backHandler.isEnabled = state == State.SENDING || state == State.PENDING || state == State.UNCONFIRMED
        binding.topBar.setNavEnabled(!sending)

        val label = when (state) {
            State.READY -> getString(R.string.withdraw_send_amount, WithdrawUi.sats(amount))
            State.SENDING -> getString(R.string.withdraw_sending)
            State.REFUSED -> getString(R.string.withdraw_try_again)
            State.PENDING, State.UNCONFIRMED -> getString(R.string.withdraw_done)
        }
        WithdrawUi.setButtonBusy(binding.sendButton, sending, label)

        if (animate) WithdrawUi.animateLayoutChange(binding.root)
        when (state) {
            State.REFUSED -> showBanner(
                R.color.color_status_error_bg, R.drawable.ic_warning, R.color.m3_error,
                R.string.withdraw_failed_title, R.string.withdraw_failed_body
            )
            State.PENDING -> showBanner(
                R.color.color_status_pending_bg, R.drawable.ic_pending, R.color.color_text_primary,
                R.string.withdraw_pending_title, R.string.withdraw_pending_body
            )
            State.UNCONFIRMED -> showBanner(
                R.color.color_status_pending_bg, R.drawable.ic_warning, R.color.color_text_primary,
                R.string.withdraw_unconfirmed_title, R.string.withdraw_unconfirmed_body
            )
            // Keep an outcome visible while retrying; clear it only once back to ready.
            State.SENDING -> Unit
            State.READY -> binding.statusBanner.visibility = View.GONE
        }
        // While a retry is sending, "Change destination" stays in place (disabled) so the
        // primary button doesn't jump down under the merchant's finger.
        val retrying = state == State.SENDING && binding.secondaryButton.isVisible
        binding.secondaryButton.visibility = if (state == State.REFUSED || retrying) View.VISIBLE else View.GONE
        binding.secondaryButton.isEnabled = state != State.SENDING
    }

    private fun showBanner(
        @ColorRes backgroundRes: Int,
        @DrawableRes iconRes: Int,
        @ColorRes iconTintRes: Int,
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int,
    ) {
        binding.statusBanner.backgroundTintList = ContextCompat.getColorStateList(this, backgroundRes)
        binding.statusIcon.setImageResource(iconRes)
        binding.statusIcon.imageTintList = ContextCompat.getColorStateList(this, iconTintRes)
        binding.statusTitle.setText(titleRes)
        binding.statusBody.setText(bodyRes)
        binding.statusBanner.visibility = View.VISIBLE
    }

    private fun destinationLabel(): String =
        lightningAddress?.takeIf { it.isNotBlank() } ?: invoice ?: request

    private fun openHub() {
        startActivity(
            Intent(this, WithdrawActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val TAG = "WithdrawMeltQuote"
        private const val UI_WAIT_MS = 20_000L
        private val meltScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private suspend fun runMelt(
            context: Context,
            mintUrl: String,
            quoteId: String,
            amount: Long,
            destination: String,
            entryId: String,
        ): MeltResult {
            val wallet = CashuWalletManager.getWallet() ?: return MeltResult.WalletUnavailable
            val manager = AutoWithdrawManager.getInstance(context)
            return try {
                val finalized = withContext(Dispatchers.IO) {
                    val unit = MintManager.getInstance(context).getPreferredUnit()
                    val mintWallet = wallet.getWallet(MintUrl(mintUrl), CashuWalletManager.getCurrencyUnit(unit))
                        ?: throw IllegalStateException("No wallet for mint $mintUrl")
                    mintWallet.prepareMelt(quoteId).confirm()
                }
                Log.d(TAG, "Melt finished: state=${finalized.state}, feePaid=${finalized.feePaid.value}")
                when (finalized.state) {
                    QuoteState.PAID -> {
                        val feePaid = finalized.feePaid.value.toLong()
                        manager.updateWithdrawalStatus(entryId, WithdrawHistoryEntry.STATUS_COMPLETED, feeSats = feePaid)
                        WalletLogger.log("OUT", amount, mintUrl, "Withdrawal successful: $destination")
                        MeltResult.Paid(feePaid)
                    }
                    // The entry stays pending: the payment may still settle.
                    QuoteState.PENDING -> MeltResult.Pending
                    else -> {
                        manager.updateWithdrawalStatus(
                            entryId,
                            WithdrawHistoryEntry.STATUS_FAILED,
                            errorMessage = context.getString(R.string.withdraw_melt_error_invoice_not_paid)
                        )
                        MeltResult.Refused
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Melt failed", e)
                when (MeltOutcome.forError(e)) {
                    MeltOutcome.REFUSED -> {
                        manager.updateWithdrawalStatus(entryId, WithdrawHistoryEntry.STATUS_FAILED, errorMessage = e.message)
                        MeltResult.Refused
                    }
                    MeltOutcome.PENDING -> MeltResult.Pending
                    MeltOutcome.UNCONFIRMED -> MeltResult.Unconfirmed
                }
            }
        }

        private const val STATE_QUOTE_ID = "quote_id"
        private const val STATE_FEE_RESERVE = "fee_reserve"
        private const val STATE_HISTORY_ID = "history_entry_id"
        private const val STATE_NAME = "state"
        const val EXTRA_MINT_URL = "mint_url"
        const val EXTRA_QUOTE_ID = "quote_id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_FEE_RESERVE = "fee_reserve"
        const val EXTRA_INVOICE = "invoice"
        const val EXTRA_LIGHTNING_ADDRESS = "lightning_address"
        const val EXTRA_REQUEST = "request"
    }
}
