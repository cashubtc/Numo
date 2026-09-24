package com.electricdreams.numo.feature.history

import android.app.Activity
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.electricdreams.numo.util.createProgressDialog
import com.electricdreams.numo.util.startActivityForResultCompat
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.ui.util.DialogHelper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.R
import androidx.appcompat.widget.PopupMenu
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.HistoryEntry
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ActivityHistoryBinding
import com.electricdreams.numo.ui.components.EmptyStateHelper
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.adapter.PaymentsHistoryAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class PaymentsHistoryActivity : AppCompatActivity(), HistoryFilterSheet.Host {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: PaymentsHistoryAdapter
    
    private var balanceReceiver: BroadcastReceiver? = null

    private var currentHistoryList = listOf<HistoryEntry>()

    private var loadHistoryJob: kotlinx.coroutines.Job? = null

    private val csvExportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                ActivityCsvExportHelper.exportActivityToCsvUri(
                    context = this,
                    uri = uri
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Let history content run behind the gesture nav pill for a modern look
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        binding.topBar.onNavClick { finish() }
        binding.overflowButton.setOnClickListener { showOverflowMenu(binding.overflowButton) }

        // Setup insights entry point
        binding.insightsButton.setOnClickListener {
            startActivity(Intent(this, com.electricdreams.numo.feature.insights.InsightsActivity::class.java))
        }

        // Setup RecyclerView
        adapter = PaymentsHistoryAdapter().apply {
            setOnItemClickListener { entry, position ->
                handleEntryClick(entry, position)
            }
            setOnItemDeleteListener { entry, position ->
                handleDeleteClick(entry, position)
            }
        }

        binding.historyRecyclerView.adapter = adapter
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)

        setupFilterRow()

        // Load and display history
        loadHistory()

        // Load wallet balance
        loadBalance()
    }

    override fun onResume() {
        super.onResume()
        // Register for balance updates
        balanceReceiver = BalanceRefreshBroadcast.createReceiver {
            loadBalance()
        }
        BalanceRefreshBroadcast.register(this, balanceReceiver!!)
        
        // Reload history when returning (e.g., after resuming a pending payment)
        loadHistory()
        loadBalance()
    }

    override fun onPause() {
        super.onPause()
        balanceReceiver?.let {
            BalanceRefreshBroadcast.unregister(this, it)
            balanceReceiver = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_TRANSACTION_DETAIL -> {
                if (resultCode == RESULT_OK && data != null) {
                    val positionToDelete = data.getIntExtra("position_to_delete", -1)
                    if (positionToDelete >= 0 && positionToDelete < currentHistoryList.size) {
                        deletePaymentFromHistory(currentHistoryList[positionToDelete])
                    }
                }
            }
            REQUEST_RESUME_PAYMENT -> {
                // Payment resumed - reload history to reflect any changes
                loadHistory()
            }
        }
    }

    private fun loadBalance() {
        if (CashuWalletManager.walletState.value == com.electricdreams.numo.core.cashu.WalletState.LOADING) {
            binding.balanceFiat?.text = "..."
            binding.balanceSats?.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            try {
                val balances = withContext(Dispatchers.IO) {
                    CashuWalletManager.getAllMintBalances()
                }
                val totalSats = balances.values.sum()

                // Display primary balance
                val preferredUnit = com.electricdreams.numo.core.util.MintManager.getInstance(this@PaymentsHistoryActivity).getPreferredUnit()
                val lowerUnit = preferredUnit.lowercase()
                val isCustomUnit = lowerUnit != "sat"
                
                if (isCustomUnit) {
                    val currency = Amount.Currency.fromCode(lowerUnit)
                    if (currency.symbol != lowerUnit.uppercase()) {
                        val valueToFormat = if (currency.isZeroDecimal()) totalSats * 100 else totalSats
                        binding.balanceSats?.text = Amount(valueToFormat, currency).toString()
                    } else {
                        binding.balanceSats?.text = "$totalSats $preferredUnit"
                    }
                    binding.balanceFiat?.visibility = View.GONE
                } else {
                    val satAmount = Amount(totalSats, Amount.Currency.BTC)
                    binding.balanceSats?.text = satAmount.toString()
                    binding.balanceSats?.visibility = View.VISIBLE

                    // Display fiat balance
                    val currencyManager = CurrencyManager.getInstance(this@PaymentsHistoryActivity)
                    val currencyCode = currencyManager.getCurrentCurrency()
                    val btcPrice = BitcoinPriceWorker.getInstance(this@PaymentsHistoryActivity).getCurrentPrice()
                    
                    if (btcPrice > 0) {
                        val fiatValue = (totalSats.toDouble() / 100_000_000.0) * btcPrice
                        val fiatCurrency = Amount.Currency.fromCode(currencyCode)
                        val fiatMinorUnits = kotlin.math.round(fiatValue * 100).toLong()
                        val fiatAmount = Amount(fiatMinorUnits, fiatCurrency)
                        binding.balanceFiat?.text = fiatAmount.toString()
                        binding.balanceFiat?.visibility = View.VISIBLE
                    } else {
                        // No price available, show sats as primary
                        binding.balanceFiat?.text = satAmount.toString()
                        binding.balanceFiat?.visibility = View.VISIBLE
                        binding.balanceSats?.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                // Silently handle - balance display is supplementary
                binding.balanceFiat?.text = ""
                binding.balanceSats?.text = ""
            }
        }
    }

    private fun handleEntryClick(entry: HistoryEntry, position: Int) {
        when (entry) {
            is PaymentHistoryEntry -> {
                when {
                    entry.isExpired() -> {
                        // Expired payments shouldn't be tappable
                    }
                    entry.isPending() -> {
                        val activeUnit = com.electricdreams.numo.core.util.MintManager.getInstance(this).getPreferredUnit()
                        if (!entry.getUnit().equals(activeUnit, ignoreCase = true)) {
                            Toast.makeText(this, getString(R.string.pos_error_pending_payment_different_unit), Toast.LENGTH_SHORT).show()
                            return
                        }
                        if (!com.electricdreams.numo.core.util.NetworkUtils.isNetworkAvailable(this)) {
                            Toast.makeText(this, getString(R.string.pos_error_no_network_pending_payment), Toast.LENGTH_SHORT).show()
                            return
                        }
                        when {
                            entry.getSwapLightningQuoteId() != null -> checkAndFinalizeSwap(entry)
                            // BTCPay pending entries have no lightning/nostr resume data —
                            // resuming would create a new invoice, so just show details.
                            entry.lightningQuoteId == null && entry.nostrNprofile == null -> showTransactionDetails(entry, position)
                            else -> resumePendingPayment(entry)
                        }
                    }
                    else -> showTransactionDetails(entry, position)
                }
            }
            is WithdrawHistoryEntry -> showTransactionDetails(entry, position)
        }
    }

    private fun checkAndFinalizeSwap(entry: PaymentHistoryEntry) {
        val progressDialog = createProgressDialog(getString(R.string.history_checking_payment_status)).apply {
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val success = com.electricdreams.numo.payment.SwapToLightningMintManager.tryFinalizePendingSwap(
                this@PaymentsHistoryActivity,
                entry
            )

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (success) {
                    Toast.makeText(
                        this@PaymentsHistoryActivity,
                        R.string.payment_request_status_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Launch success screen
                    val intent = Intent(this@PaymentsHistoryActivity, com.electricdreams.numo.PaymentReceivedActivity::class.java).apply {
                        putExtra(com.electricdreams.numo.PaymentReceivedActivity.EXTRA_TOKEN, "")
                        putExtra(com.electricdreams.numo.PaymentReceivedActivity.EXTRA_AMOUNT, entry.amount)
                    }
                    startActivity(intent)
                    
                    // Reload list
                    loadHistory()
                } else {
                    // Fall back to resume if not finalized
                    resumePendingPayment(entry)
                }
            }
        }
    }

    private fun resumePendingPayment(entry: PaymentHistoryEntry) {
        val intent = PaymentIntentFactory.createResumePaymentIntent(this, entry)
        startActivityForResultCompat(intent, REQUEST_RESUME_PAYMENT)
    }

    private fun showTransactionDetails(entry: HistoryEntry, position: Int) {
        val intent = PaymentIntentFactory.createTransactionDetailIntent(this, entry, position)
        startActivityForResultCompat(intent, REQUEST_TRANSACTION_DETAIL)
    }

    private fun openPaymentWithApp(token: String) {
        val cashuUri = "cashu:$token"
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cashuUri)
        }

        val chooserIntent = Intent.createChooser(uriIntent, getString(R.string.history_open_with_title)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
        }

        try {
            startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.history_toast_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeleteClick(entry: HistoryEntry, position: Int) {
        if (entry.isPending()) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val hideWarning = prefs.getBoolean("hide_pending_delete_warning", false)
            if (hideWarning) {
                deletePaymentFromHistory(entry)
            } else {
                showPendingDeleteWarning(entry)
            }
        } else {
            deletePaymentFromHistory(entry)
        }
    }

    private fun showPendingDeleteWarning(entry: HistoryEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_pending_delete_warning, null)
        val checkbox = view.findViewById<android.widget.CheckBox>(R.id.dont_show_again_checkbox)

        AlertDialog.Builder(this)
            .setTitle(R.string.history_dialog_pending_delete_title)
            .setMessage(R.string.history_dialog_pending_delete_message)
            .setView(view)
            .setPositiveButton(R.string.history_dialog_delete_positive) { _, _ ->
                if (checkbox.isChecked) {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putBoolean("hide_pending_delete_warning", true).apply()
                }
                deletePaymentFromHistory(entry)
            }
            .setNegativeButton(R.string.history_dialog_delete_negative, null)
            .show()
    }


    private fun showClearHistoryConfirmation() {
        DialogHelper.showConfirmation(this, DialogHelper.ConfirmationConfig(
            title = getString(R.string.history_dialog_clear_title),
            message = getString(R.string.history_dialog_clear_message),
            confirmText = getString(R.string.history_dialog_clear_positive),
            isDestructive = true,
            onConfirm = { clearAllHistory() }
        ))
    }

    override val historyFilter: HistoryFilter
        get() = HistoryFilter.load(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))

    override fun applyHistoryFilter(filter: HistoryFilter) {
        HistoryFilter.save(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), filter)
        renderFilterRow(filter)
        filterSheet()?.render(filter)
        // A new result set starts at the top with the balance showing; a short or empty list
        // could otherwise leave the header collapsed with no way to scroll it back.
        binding.historyRecyclerView.scrollToPosition(0)
        binding.historyAppBar.setExpanded(true, true)
        loadHistory()
    }

    private fun filterSheet(): HistoryFilterSheet? =
        supportFragmentManager.findFragmentByTag(HistoryFilterSheet.TAG) as? HistoryFilterSheet

    private fun setupFilterRow() {
        binding.filterButton.setOnClickListener {
            if (filterSheet() == null) {
                HistoryFilterSheet().show(supportFragmentManager, HistoryFilterSheet.TAG)
            }
        }

        // Hairline under the pinned filter row once the balance has scrolled away
        binding.historyAppBar.addOnOffsetChangedListener { appBar, verticalOffset ->
            val collapsed = appBar.totalScrollRange > 0 && -verticalOffset >= appBar.totalScrollRange
            val target = if (collapsed) 1f else 0f
            if (binding.filterRowDivider.alpha != target) {
                binding.filterRowDivider.animate().alpha(target).setDuration(150).start()
            }
        }

        renderFilterRow(historyFilter)
    }

    /** "Filter" / "Filter · N" pill plus one removable chip per active filter. */
    private fun renderFilterRow(filter: HistoryFilter) {
        val count = filter.activeCount
        binding.filterButton.text = if (count == 0) {
            getString(R.string.history_filter_button)
        } else {
            getString(R.string.history_filter_button_count, count)
        }
        binding.filterButton.isSelected = count > 0

        val chips = binding.activeFilterChips
        chips.removeAllViews()
        if (filter.isStatusActive) {
            chips.addView(activeFilterChip(filter.status.label(this)) {
                applyHistoryFilter(historyFilter.withoutStatus())
            })
        }
        if (filter.isDateActive) {
            chips.addView(activeFilterChip(filter.dateLabel(this)) {
                applyHistoryFilter(historyFilter.withoutDate())
            })
        }
    }

    private fun activeFilterChip(label: String, onRemove: () -> Unit): Chip {
        val chip = layoutInflater.inflate(R.layout.item_active_filter_chip, binding.activeFilterChips, false) as Chip
        chip.text = label
        chip.closeIconContentDescription = getString(R.string.history_filter_remove, label)
        chip.setOnCloseIconClickListener { onRemove() }
        chip.setOnClickListener { binding.filterButton.performClick() }
        return chip
    }

    override fun pickCustomDateRange() {
        val current = historyFilter

        // Find the oldest transaction to constrain the picker's start date
        val paymentHistory: List<HistoryEntry> = getPaymentHistory()
        val withdrawHistory: List<HistoryEntry> = AutoWithdrawManager.getInstance(this)
            .getHistory()
            .filter { it.status != WithdrawHistoryEntry.STATUS_FAILED }
        val oldestDate = (paymentHistory + withdrawHistory).minByOrNull { it.date.time }?.date?.time

        val today = MaterialDatePicker.todayInUtcMilliseconds()

        // Give a 1-month buffer before the oldest transaction, or default to 2023 if empty
        val startBounds = oldestDate?.let { it - (30L * 24 * 60 * 60 * 1000) } ?: run {
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.set(2023, java.util.Calendar.JANUARY, 1)
            calendar.timeInMillis
        }

        val constraints = CalendarConstraints.Builder()
            .setStart(startBounds)
            .setEnd(today)
            .setValidator(DateValidatorPointBackward.now())
            .build()

        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.history_filter_date_picker_title)
            .setCalendarConstraints(constraints)

        if (current.datePreset == HistoryDatePreset.CUSTOM) {
            val validStart = current.customStartUtc.coerceAtLeast(startBounds)
            val validEnd = current.customEndUtc.coerceAtMost(today)
            if (validStart <= validEnd) {
                builder.setSelection(androidx.core.util.Pair(validStart, validEnd))
            }
        }

        val picker = builder.build()
        var picked = false
        picker.addOnPositiveButtonClickListener { selection ->
            val start = selection.first
            val end = selection.second
            if (start != null && end != null) {
                picked = true
                applyHistoryFilter(
                    historyFilter.copy(
                        datePreset = HistoryDatePreset.CUSTOM,
                        customStartUtc = start,
                        customEndUtc = end,
                    )
                )
            }
        }
        // Cancelled: put the sheet's date chips back to the filter in effect
        picker.addOnDismissListener {
            if (!picked) filterSheet()?.render(historyFilter)
        }
        picker.show(supportFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_activity_history, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_export_activity -> {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    csvExportLauncher.launch("numo_activity_export_$dateStr.csv")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun loadHistory() {
        loadHistoryJob?.cancel()
        loadHistoryJob = lifecycleScope.launch {
            val (filteredList, activeFilterCount) = withContext(ioDispatcher) {
                // Stale BTCPay pending entries (no resume data) will never be resolved by polling
                // if the app was killed mid-flow — expire them now so they don't sit as "Pending" forever.
                expireStaleBtcPayEntries()

                val appContext = this@PaymentsHistoryActivity.applicationContext
                val prefs = appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val filter = HistoryFilter.load(prefs)
                val now = System.currentTimeMillis()

                val paymentHistory: List<HistoryEntry> = getPaymentHistory(appContext)
                val withdrawHistory: List<HistoryEntry> = AutoWithdrawManager.getInstance(appContext)
                    .getHistory()
                    .filter { it.status != WithdrawHistoryEntry.STATUS_FAILED }

                // Merge, filter and sort by date descending (newest first)
                val list = (paymentHistory + withdrawHistory)
                    .filter { filter.matches(it, now) }
                    .sortedByDescending { it.date.time }

                list to filter.activeCount
            }

            currentHistoryList = filteredList
            adapter.setEntries(currentHistoryList)

            val isEmptyList = filteredList.isEmpty()
            binding.emptyView.root.visibility = if (isEmptyList) View.VISIBLE else View.GONE
            if (isEmptyList) {
                if (activeFilterCount > 0) {
                    EmptyStateHelper.bind(
                        binding.emptyView.root,
                        R.drawable.ic_tune,
                        getString(R.string.history_empty_filtered_title),
                        getString(R.string.history_empty_filtered_subtitle)
                    )
                } else {
                    EmptyStateHelper.bind(
                        binding.emptyView.root,
                        R.drawable.ic_receipt,
                        getString(R.string.history_empty),
                        getString(R.string.history_empty_subtitle)
                    )
                }
            }
        }
    }

    private fun clearAllHistory() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, "[]").apply()
        loadHistory()
    }

    private fun deletePaymentFromHistory(entry: HistoryEntry) {
        if (entry is PaymentHistoryEntry) {
            val history = getPaymentHistory().toMutableList()
            val index = history.indexOfFirst { it.id == entry.id }
            if (index >= 0) {
                history.removeAt(index)
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
                loadHistory()
            }
        } else if (entry is WithdrawHistoryEntry) {
            AutoWithdrawManager.getInstance(this).deleteHistoryEntry(entry.id)
            loadHistory()
        }
    }

    /**
     * Expire pending entries that can no longer be resolved:
     * - Orphaned entries with no resume data
     * - Entries older than [STALE_PENDING_THRESHOLD_MS]
     * - BTCPay entries when BTCPay is disabled (they store the BTCPay invoice ID
     *   in [PaymentHistoryEntry.lightningQuoteId] but have no lightningMintUrl/nostrNprofile;
     *   without an active BTCPay connection these can never settle)
     */
    private fun expireStaleBtcPayEntries() {
        val appContext = applicationContext
        val history = getPaymentHistory(appContext).toMutableList()
        val cutoff = System.currentTimeMillis() - STALE_PENDING_THRESHOLD_MS
        val btcPayEnabled = PreferenceStore.app(appContext).getBoolean("btcpay_enabled", false)

        var modified = false
        history.forEachIndexed { index, entry ->
            if (entry.isPending() && (
                // Old enough that no invoice type would still be valid
                entry.date.time < cutoff ||
                // BTCPay is disabled — pending BTCPay entries can never be resolved.
                // BTCPay entries have lightningQuoteId (set to invoice ID) but no
                // lightningMintUrl (local Lightning) or nostrNprofile (Nostr).
                (!btcPayEnabled && entry.lightningQuoteId != null
                    && entry.lightningMintUrl == null && entry.nostrNprofile == null)
            )) {
                val updated = PaymentHistoryEntry(
                    id = entry.id,
                    token = entry.token,
                    amount = entry.amount,
                    date = entry.date,
                    rawUnit = entry.getUnit(),
                    rawEntryUnit = entry.getEntryUnit(),
                    enteredAmount = entry.enteredAmount,
                    bitcoinPrice = entry.bitcoinPrice,
                    mintUrl = entry.mintUrl,
                    paymentRequest = entry.paymentRequest,
                    rawStatus = PaymentHistoryEntry.STATUS_EXPIRED,
                    paymentType = entry.paymentType,
                    lightningInvoice = entry.lightningInvoice,
                    lightningQuoteId = entry.lightningQuoteId,
                    lightningMintUrl = entry.lightningMintUrl,
                    formattedAmount = entry.formattedAmount,
                    nostrNprofile = entry.nostrNprofile,
                    nostrSecretHex = entry.nostrSecretHex,
                    checkoutBasketJson = entry.checkoutBasketJson,
                    basketId = entry.basketId,
                    tipAmountSats = entry.tipAmountSats,
                    tipPercentage = entry.tipPercentage,
                    swapToLightningMintJson = entry.swapToLightningMintJson,
                )
                history[index] = updated
                modified = true
            }
        }

        if (modified) {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }
    }

    private fun getPaymentHistory(): List<PaymentHistoryEntry> = getPaymentHistory(this)

    companion object {
        @Volatile
        var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

        private const val PREFS_NAME = "PaymentHistory"
        private const val KEY_HISTORY = "history"
        private const val REQUEST_TRANSACTION_DETAIL = 1001
        private const val REQUEST_RESUME_PAYMENT = 1002
        // Pending payments older than this are considered stale regardless of resume data.
        // BTCPay invoices default to 15min; local Lightning quotes also expire. 2h is generous.
        private const val STALE_PENDING_THRESHOLD_MS = 2 * 60 * 60 * 1000L

        @JvmStatic
        fun getPaymentHistory(context: Context): List<PaymentHistoryEntry> {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = prefs.getString(KEY_HISTORY, "[]")
            val type: Type = object : TypeToken<ArrayList<PaymentHistoryEntry>>() {}.type
            return Gson().fromJson(json, type)
        }

        @JvmStatic
        fun getPaymentEntryById(context: Context, paymentId: String): PaymentHistoryEntry? {
            return getPaymentHistory(context).firstOrNull { it.id == paymentId }
        }

        /**
         * Add a pending payment to history when payment request is initiated.
         * Returns the ID of the created entry.
         */
        @JvmStatic
        fun addPendingPayment(
            context: Context,
            amount: Long,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            paymentRequest: String?,
            formattedAmount: String?,
            checkoutBasketJson: String? = null,
            basketId: String? = null,
            tipAmountSats: Long = 0,
            tipPercentage: Int = 0,
        ): String {
            val ecashUnit = com.electricdreams.numo.core.util.MintManager.getInstance(context).getPreferredUnit()
            val entry = PaymentHistoryEntry.createPending(
                amount = amount,
                entryUnit = entryUnit,
                enteredAmount = enteredAmount,
                bitcoinPrice = bitcoinPrice,
                paymentRequest = paymentRequest,
                formattedAmount = formattedAmount,
                checkoutBasketJson = checkoutBasketJson,
                basketId = basketId,
                tipAmountSats = tipAmountSats,
                tipPercentage = tipPercentage,
                ecashUnit = ecashUnit,
            )

            val history = getPaymentHistory(context).toMutableList()
            history.add(entry)

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()

            return entry.id
        }

        /**
         * Update a pending payment to completed with full payment details.
         */
        @JvmStatic
        fun completePendingPayment(
            context: Context,
            paymentId: String,
            token: String,
            paymentType: String,
            mintUrl: String?,
            lightningInvoice: String? = null,
            lightningQuoteId: String? = null,
            lightningMintUrl: String? = null,
            btcPayInvoiceId: String? = null,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            // Only complete entries that are still pending — never overwrite expired/cancelled status
            val index = history.indexOfFirst { it.id == paymentId && it.isPending() }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = mintUrl ?: existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
                    paymentType = paymentType,
                    lightningInvoice = lightningInvoice,
                    lightningQuoteId = lightningQuoteId,
                    lightningMintUrl = lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson,
                    basketId = existing.basketId,
                    tipAmountSats = existing.tipAmountSats,
                    tipPercentage = existing.tipPercentage,
                    label = existing.label,
                    btcPayInvoiceId = btcPayInvoiceId ?: existing.btcPayInvoiceId,
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Update a pending payment with Lightning quote info (for resume capability).
         */
        @JvmStatic
        fun updatePendingWithLightningInfo(
            context: Context,
            paymentId: String,
            lightningInvoice: String? = null,
            lightningQuoteId: String? = null,
            lightningMintUrl: String? = null,
            swapToLightningMintJson: String? = null,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.status,
                    paymentType = existing.paymentType,
                    lightningInvoice = lightningInvoice ?: existing.lightningInvoice,
                    lightningQuoteId = lightningQuoteId ?: existing.lightningQuoteId,
                    lightningMintUrl = lightningMintUrl ?: existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
                    swapToLightningMintJson = swapToLightningMintJson ?: existing.swapToLightningMintJson,
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Update a pending payment with Nostr info (for resume capability).
         */
        @JvmStatic
        fun updatePendingWithNostrInfo(
            context: Context,
            paymentId: String,
            nostrSecretHex: String,
            nostrNprofile: String,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.status,
                    paymentType = existing.paymentType,
                    lightningInvoice = existing.lightningInvoice,
                    lightningQuoteId = existing.lightningQuoteId,
                    lightningMintUrl = existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = nostrNprofile,
                    nostrSecretHex = nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
                    label = existing.label, // Preserve label
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).commit()
            }
        }

        /**
         * Update a pending payment with tip information.
         */
        @JvmStatic
        fun updatePendingWithTipInfo(
            context: Context,
            paymentId: String,
            tipAmountSats: Long,
            tipPercentage: Int,
            newTotalAmount: Long,
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = newTotalAmount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.status,
                    paymentType = existing.paymentType,
                    lightningInvoice = existing.lightningInvoice,
                    lightningQuoteId = existing.lightningQuoteId,
                    lightningMintUrl = existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson,
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = tipAmountSats,
                    tipPercentage = tipPercentage,
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Mark a pending payment as expired (BTCPay invoice expired before payment).
         * Keeps the entry in history unlike [cancelPendingPayment].
         */
        @JvmStatic
        fun markPaymentExpired(context: Context, paymentId: String) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId && it.isPending() }
            if (index == -1) return

            val existing = history[index]
            val updated = PaymentHistoryEntry(
                id = existing.id,
                token = existing.token,
                amount = existing.amount,
                date = existing.date,
                rawUnit = existing.getUnit(),
                rawEntryUnit = existing.getEntryUnit(),
                enteredAmount = existing.enteredAmount,
                bitcoinPrice = existing.bitcoinPrice,
                mintUrl = existing.mintUrl,
                paymentRequest = existing.paymentRequest,
                rawStatus = PaymentHistoryEntry.STATUS_EXPIRED,
                paymentType = existing.paymentType,
                lightningInvoice = existing.lightningInvoice,
                lightningQuoteId = existing.lightningQuoteId,
                lightningMintUrl = existing.lightningMintUrl,
                formattedAmount = existing.formattedAmount,
                nostrNprofile = existing.nostrNprofile,
                nostrSecretHex = existing.nostrSecretHex,
                checkoutBasketJson = existing.checkoutBasketJson,
                basketId = existing.basketId,
                tipAmountSats = existing.tipAmountSats,
                tipPercentage = existing.tipPercentage,
                swapToLightningMintJson = existing.swapToLightningMintJson,
            )
            history[index] = updated

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }

        fun markPaymentFailed(context: Context, paymentId: String) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId && it.isPending() }
            if (index == -1) return

            val existing = history[index]
            val updated = PaymentHistoryEntry(
                id = existing.id,
                token = existing.token,
                amount = existing.amount,
                date = existing.date,
                rawUnit = existing.getUnit(),
                rawEntryUnit = existing.getEntryUnit(),
                enteredAmount = existing.enteredAmount,
                bitcoinPrice = existing.bitcoinPrice,
                mintUrl = existing.mintUrl,
                paymentRequest = existing.paymentRequest,
                rawStatus = PaymentHistoryEntry.STATUS_FAILED,
                paymentType = existing.paymentType,
                lightningInvoice = existing.lightningInvoice,
                lightningQuoteId = existing.lightningQuoteId,
                lightningMintUrl = existing.lightningMintUrl,
                formattedAmount = existing.formattedAmount,
                nostrNprofile = existing.nostrNprofile,
                nostrSecretHex = existing.nostrSecretHex,
                checkoutBasketJson = existing.checkoutBasketJson,
                basketId = existing.basketId,
                tipAmountSats = existing.tipAmountSats,
                tipPercentage = existing.tipPercentage,
                swapToLightningMintJson = existing.swapToLightningMintJson,
            )
            history[index] = updated

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }

        /**
         * Cancel a pending payment (mark as cancelled or delete).
         */
        @JvmStatic
        fun cancelPendingPayment(context: Context, paymentId: String) {
            val history = getPaymentHistory(context).toMutableList()
            // Remove cancelled pending payments (they're not useful)
            history.removeAll { it.id == paymentId && it.isPending() }

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }

        /**
         * Update the label on a payment history entry.
         */
        @JvmStatic
        fun updateLabel(context: Context, paymentId: String, label: String?) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

            if (index >= 0) {
                val existing = history[index]
                val updated = PaymentHistoryEntry(
                    id = existing.id,
                    token = existing.token,
                    amount = existing.amount,
                    date = existing.date,
                    rawUnit = existing.getUnit(),
                    rawEntryUnit = existing.getEntryUnit(),
                    enteredAmount = existing.enteredAmount,
                    bitcoinPrice = existing.bitcoinPrice,
                    mintUrl = existing.mintUrl,
                    paymentRequest = existing.paymentRequest,
                    rawStatus = existing.status,
                    paymentType = existing.paymentType,
                    lightningInvoice = existing.lightningInvoice,
                    lightningQuoteId = existing.lightningQuoteId,
                    lightningMintUrl = existing.lightningMintUrl,
                    formattedAmount = existing.formattedAmount,
                    nostrNprofile = existing.nostrNprofile,
                    nostrSecretHex = existing.nostrSecretHex,
                    checkoutBasketJson = existing.checkoutBasketJson,
                    basketId = existing.basketId,
                    tipAmountSats = existing.tipAmountSats,
                    tipPercentage = existing.tipPercentage,
                    swapToLightningMintJson = existing.swapToLightningMintJson,
                    label = label?.ifBlank { null },
                )
                history[index] = updated

                val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
            }
        }

        /**
         * Add a payment to history with comprehensive information (legacy method).
         */
        @JvmStatic
        fun addToHistory(
            context: Context,
            token: String,
            amount: Long,
            unit: String,
            entryUnit: String,
            enteredAmount: Long,
            bitcoinPrice: Double?,
            mintUrl: String?,
            paymentRequest: String?,
        ): String {
            val history = getPaymentHistory(context).toMutableList()
            val entry = PaymentHistoryEntry(
                token = token,
                amount = amount,
                date = java.util.Date(),
                rawUnit = unit,
                rawEntryUnit = entryUnit,
                enteredAmount = enteredAmount,
                bitcoinPrice = bitcoinPrice,
                mintUrl = mintUrl,
                paymentRequest = paymentRequest,
                rawStatus = PaymentHistoryEntry.STATUS_COMPLETED,
                paymentType = PaymentHistoryEntry.TYPE_CASHU,
            )
            history.add(entry)

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()

            return entry.id
        }

        /**
         * Legacy method for backward compatibility.
         * @deprecated Use addToHistory with full parameters.
         */
        @Deprecated("Use addToHistory with full parameters")
        @JvmStatic
        fun addToHistory(context: Context, token: String, amount: Long) {
            addToHistory(context, token, amount, "sat", "sat", amount, null, null, null)
        }
    }
}
