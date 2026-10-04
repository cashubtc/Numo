package com.electricdreams.numo.feature.settings

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.MeltQuote
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod

import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.ActivityWithdrawLightningBinding
import com.electricdreams.numo.feature.scanner.QRScannerActivity
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Send to wallet: one "To" field that accepts a Lightning address or an invoice,
 * an amount only when the destination needs one, and a single Review action.
 */
class WithdrawLightningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawLightningBinding
    private lateinit var sourcePicker: WithdrawSourcePicker
    private lateinit var amountField: WithdrawAmountField
    private lateinit var lightningAddressManager: LightningAddressManager

    private var destination: WithdrawDestination = WithdrawDestination.Empty
    private var isQuoting = false
    private var reviewOpened = false

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(QRScannerActivity.EXTRA_QR_VALUE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setDestinationText(it) }
            }
        }

    private val balanceRefreshReceiver: BroadcastReceiver =
        BalanceRefreshBroadcast.createReceiver { reason ->
            Log.d(TAG, "Balance refresh broadcast received: $reason")
            if (!isQuoting) sourcePicker.load()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWithdrawLightningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        lightningAddressManager = LightningAddressManager.getInstance(this)
        amountField = WithdrawAmountField(this, binding.amount) { updateReviewEnabled() }
        sourcePicker = WithdrawSourcePicker(this, binding.source) { source ->
            val balance = source?.balance ?: 0L
            amountField.setAvailable(balance, maxSendable = suggestedAmount(balance))
            // An invoice's amount is checked against the balance of the chosen source.
            onDestinationChanged(showInvalid = binding.toLayout.error != null)
        }

        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        setupDestinationField()
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.reviewButton.setOnClickListener { requestQuote() }

        if (savedInstanceState == null) {
            val savedAddress = lightningAddressManager.getLightningAddress()
            if (savedAddress.isNotBlank()) setDestinationText(savedAddress)
        }
        sourcePicker.load()
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
        // Coming back from Review: that quote was not used, so the form is editable again.
        if (reviewOpened) {
            reviewOpened = false
            setQuoting(false)
        }
        if (!isQuoting) sourcePicker.load()
    }

    private fun setupDestinationField() {
        // Single-line input semantics (so the keyboard's Done works) that still wrap a long
        // invoice over a few lines instead of scrolling it sideways.
        binding.toInput.setHorizontallyScrolling(false)
        binding.toInput.maxLines = TO_FIELD_MAX_LINES
        binding.toInput.doAfterTextChanged { onDestinationChanged(showInvalid = false) }
        binding.toInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                onDestinationChanged(showInvalid = true)
                showInvoiceStart()
            }
        }
        binding.toInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onDestinationChanged(showInvalid = true)
            }
            false
        }
        binding.toLayout.setEndIconOnClickListener {
            if (binding.toInput.text.isNullOrEmpty()) launchScanner() else binding.toInput.text = null
        }
    }

    private fun setDestinationText(raw: String) {
        // Show the cleaned value (no "lightning:" prefix or line breaks) so what the
        // merchant sees is exactly what will be paid.
        val parsed = parse(raw)
        val text = when (parsed) {
            is WithdrawDestination.Invoice -> parsed.invoice
            is WithdrawDestination.LightningAddress -> parsed.address
            else -> raw.trim()
        }
        binding.toInput.setText(text)
        binding.toInput.setSelection(text.length)
        onDestinationChanged(showInvalid = true)
        showInvoiceStart()
    }

    /** A wrapped invoice reads from its recognisable "lnbc…" start, never a clipped middle. */
    private fun showInvoiceStart() {
        if (destination is WithdrawDestination.Invoice) {
            binding.toInput.setSelection(0)
            binding.toInput.scrollTo(0, 0)
        }
    }

    private fun onDestinationChanged(showInvalid: Boolean) {
        destination = parse(binding.toInput.text?.toString().orEmpty())

        val hasText = destination != WithdrawDestination.Empty
        binding.toLayout.setEndIconDrawable(if (hasText) R.drawable.ic_close else R.drawable.ic_qr_scan)
        binding.toLayout.endIconContentDescription =
            getString(if (hasText) R.string.withdraw_to_clear else R.string.withdraw_to_scan)

        when (val d = destination) {
            WithdrawDestination.Empty -> showToStatus(helper = getString(R.string.withdraw_to_helper))
            is WithdrawDestination.LightningAddress ->
                showToStatus(helper = getString(R.string.withdraw_to_detected_address))
            is WithdrawDestination.Invoice -> {
                val helper = d.amountSats?.let {
                    getString(R.string.withdraw_to_detected_invoice_amount, WithdrawUi.sats(it))
                } ?: getString(R.string.withdraw_to_detected_invoice)
                // Until the source has loaded there is no balance to compare against.
                val available = sourcePicker.current?.balance
                if (d.amountSats != null && available != null && d.amountSats > available) {
                    showToStatus(error = getString(R.string.withdraw_amount_too_large, WithdrawUi.sats(available)))
                } else {
                    showToStatus(helper = helper)
                }
            }
            is WithdrawDestination.Invalid -> {
                if (showInvalid) {
                    showToStatus(error = getString(R.string.withdraw_to_invalid))
                } else {
                    showToStatus(helper = getString(R.string.withdraw_to_helper))
                }
            }
        }

        // An invoice carries its own amount; everything else needs one from the merchant.
        val needsAmount = destination !is WithdrawDestination.Invoice
        if (binding.amount.root.isVisible != needsAmount) {
            WithdrawUi.animateLayoutChange(binding.root)
            binding.amount.root.visibility = if (needsAmount) View.VISIBLE else View.GONE
        }
        updateReviewEnabled()
    }

    /**
     * The line under the field shows either what was detected or what is wrong. Moving
     * between the two can change its height, so the content below glides instead of jumping.
     */
    private fun showToStatus(helper: String? = null, error: String? = null) {
        val layout = binding.toLayout
        val changesKind = (layout.error != null) != (error != null)
        if (changesKind) WithdrawUi.animateLayoutChange(binding.root)
        layout.error = error
        if (error == null) layout.helperText = helper
    }

    private fun updateReviewEnabled() {
        val source = sourcePicker.current
        binding.reviewButton.isEnabled = !isQuoting && source != null && when (val d = destination) {
            is WithdrawDestination.LightningAddress -> amountField.isValid
            is WithdrawDestination.Invoice -> d.amountSats == null || d.amountSats <= source.balance
            else -> false
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            WithdrawUi.snackbar(binding.reviewButton, getString(R.string.withdraw_paste_empty))
        } else {
            setDestinationText(text)
        }
    }

    private fun launchScanner() {
        val intent = Intent(this, QRScannerActivity::class.java)
            .putExtra(QRScannerActivity.EXTRA_TITLE, getString(R.string.withdraw_scan_qr))
        scanLauncher.launch(intent)
    }

    private fun requestQuote() {
        val source = sourcePicker.current ?: return
        val target = destination
        if (target is WithdrawDestination.Invalid || target == WithdrawDestination.Empty) {
            onDestinationChanged(showInvalid = true)
            return
        }

        setQuoting(true)
        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    setQuoting(false)
                    WithdrawUi.snackbar(binding.reviewButton, getString(R.string.withdraw_error_wallet))
                    return@launch
                }
                val unit = MintManager.getInstance(this@WithdrawLightningActivity).getPreferredUnit()
                val mintWallet = wallet.getWallet(
                    MintUrl(source.mintUrl),
                    CashuWalletManager.getCurrencyUnit(unit)
                ) ?: throw IllegalStateException("No wallet for mint ${source.mintUrl}")

                val quote = withContext(Dispatchers.IO) {
                    when (target) {
                        is WithdrawDestination.Invoice ->
                            mintWallet.meltQuote(PaymentMethod.Bolt11, target.invoice, null, null)
                        is WithdrawDestination.LightningAddress -> mintWallet.meltLightningAddressQuote(
                            target.address,
                            org.cashudevkit.Amount((amountField.value * 1000).toULong())
                        )
                        else -> throw IllegalStateException("Unsupported destination")
                    }
                }
                WalletLogger.log("OUT", quote.amount.value.toLong(), source.mintUrl, "Melt quote requested")
                onQuoteReady(source, target, quote)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to get melt quote", e)
                setQuoting(false)
                showToStatus(error = getString(R.string.withdraw_error_quote))
            }
        }
    }

    private fun onQuoteReady(source: WithdrawSource, target: WithdrawDestination, quote: MeltQuote) {
        val total = quote.amount.value.toLong() + quote.feeReserve.value.toLong()
        if (total > source.balance) {
            setQuoting(false)
            val feeReserve = quote.feeReserve.value.toLong()
            if (target is WithdrawDestination.LightningAddress) {
                val sendable = (source.balance - feeReserve).coerceAtLeast(0)
                amountField.setError(getString(R.string.withdraw_error_insufficient, WithdrawUi.sats(sendable)))
            } else {
                showToStatus(error = getString(R.string.withdraw_error_insufficient_invoice))
            }
            return
        }

        val address = (target as? WithdrawDestination.LightningAddress)?.address
        // The saved address is also where auto-withdraw sends, so a one-off payment to
        // someone else must never replace it; it only fills it in when none is set yet.
        if (address != null && !lightningAddressManager.hasLightningAddress()) {
            lightningAddressManager.setLightningAddress(address)
        }

        val intent = Intent(this, WithdrawMeltQuoteActivity::class.java).apply {
            putExtra(WithdrawMeltQuoteActivity.EXTRA_MINT_URL, source.mintUrl)
            putExtra(WithdrawMeltQuoteActivity.EXTRA_QUOTE_ID, quote.id)
            putExtra(WithdrawMeltQuoteActivity.EXTRA_AMOUNT, quote.amount.value.toLong())
            putExtra(WithdrawMeltQuoteActivity.EXTRA_FEE_RESERVE, quote.feeReserve.value.toLong())
            putExtra(WithdrawMeltQuoteActivity.EXTRA_INVOICE, (target as? WithdrawDestination.Invoice)?.invoice)
            putExtra(WithdrawMeltQuoteActivity.EXTRA_LIGHTNING_ADDRESS, address)
            putExtra(WithdrawMeltQuoteActivity.EXTRA_REQUEST, quote.request)
        }
        reviewOpened = true
        startActivity(intent)
    }

    private fun setQuoting(quoting: Boolean) {
        isQuoting = quoting
        binding.toLayout.isEnabled = !quoting
        binding.pasteButton.isEnabled = !quoting
        amountField.setEnabled(!quoting)
        WithdrawUi.setButtonBusy(
            binding.reviewButton,
            quoting,
            getString(if (quoting) R.string.withdraw_review_loading else R.string.withdraw_review)
        )
        updateReviewEnabled()
        if (quoting) binding.reviewButton.isEnabled = true
    }

    private fun parse(raw: String): WithdrawDestination =
        WithdrawDestination.parse(raw) { lightningAddressManager.isValidLightningAddress(it) }

    /** Leaves room for Lightning routing fees, which the mint reserves up front. */
    private fun suggestedAmount(balance: Long): Long = (balance * (1 - FEE_BUFFER_PERCENT)).toLong()

    companion object {
        private const val TAG = "WithdrawLightning"
        private const val FEE_BUFFER_PERCENT = 0.02
        private const val TO_FIELD_MAX_LINES = 3
    }
}
