package com.electricdreams.numo.feature.history

import android.app.Activity
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.electricdreams.numo.util.createProgressDialog
import com.electricdreams.numo.util.startActivityForResultCompat
import android.view.View
import android.widget.Toast
import com.google.android.material.chip.Chip
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.R
import androidx.appcompat.widget.PopupMenu
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.HistoryEntry
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ActivityHistoryBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.payment.PaymentIntentFactory
import com.electricdreams.numo.ui.adapter.PaymentsHistoryAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

class PaymentsHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: PaymentsHistoryAdapter
    
    private var balanceReceiver: BroadcastReceiver? = null

    private var currentHistoryList = listOf<HistoryEntry>()

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

        // Setup Back Button
        binding.backButton?.setOnClickListener { finish() }

        // Setup overflow menu button
        binding.overflowButton?.setOnClickListener { showOverflowMenu(it) }

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

        migrateLegacyFilterStateIfNeeded()
        registerDateFilterResultListener()
        findViewById<Chip>(R.id.active_filter_indicator).apply {
            setOnClickListener { clearAllFilters() }
            setOnCloseIconClickListener { clearAllFilters() }
        }
        updateActiveFilterIndicator()

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

                // Display sat balance
                val satAmount = Amount(totalSats, Amount.Currency.BTC)
                binding.balanceSats?.text = satAmount.toString()
                binding.balanceSats?.visibility = View.VISIBLE

                // Display fiat balance
                val currencyCode = CurrencyManager.getInstance(this@PaymentsHistoryActivity)
                    .getCurrentCurrency()
                val btcPrice = BitcoinPriceWorker.getInstance(this@PaymentsHistoryActivity)
                    .getCurrentPrice()

                if (btcPrice > 0) {
                    val fiatValue = (totalSats.toDouble() / 100_000_000.0) * btcPrice
                    val fiatCurrency = Amount.Currency.fromCode(currencyCode)
                    val fiatMinorUnits = kotlin.math.round(fiatValue * 100).toLong()
                    val fiatAmount = Amount(fiatMinorUnits, fiatCurrency)
                    binding.balanceFiat?.text = fiatAmount.toString()
                } else {
                    // No price available, show sats as primary
                    binding.balanceFiat?.text = satAmount.toString()
                    binding.balanceSats?.visibility = View.GONE
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
                if (entry.isPending()) {
                    if (!com.electricdreams.numo.core.util.NetworkUtils.isNetworkAvailable(this)) {
                        Toast.makeText(this, getString(R.string.pos_error_no_network_pending_payment), Toast.LENGTH_SHORT).show()
                        return
                    }
                    // Check if this is a pending swap-to-lightning-mint flow
                    if (entry.getSwapLightningQuoteId() != null) {
                        checkAndFinalizeSwap(entry)
                    } else {
                        // Resume the pending payment normally
                        resumePendingPayment(entry)
                    }
                } else {
                    showTransactionDetails(entry, position)
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

    private fun showDeleteConfirmation(entry: HistoryEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_dialog_delete_title)
            .setMessage(R.string.history_dialog_delete_message)
            .setPositiveButton(R.string.history_dialog_delete_positive) { _, _ -> deletePaymentFromHistory(entry) }
            .setNegativeButton(R.string.history_dialog_delete_negative, null)
            .show()
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_dialog_clear_title)
            .setMessage(R.string.history_dialog_clear_message)
            .setPositiveButton(R.string.history_dialog_clear_positive) { _, _ -> clearAllHistory() }
            .setNegativeButton(R.string.history_dialog_clear_negative, null)
            .show()
    }

    private fun migrateLegacyFilterStateIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains(LEGACY_KEY_FILTER_STATE) || prefs.contains(KEY_HIDE_PENDING)) return
        val legacy = prefs.getInt(LEGACY_KEY_FILTER_STATE, LEGACY_FILTER_PAID)
        val hidePending = legacy == LEGACY_FILTER_PAID
        prefs.edit()
            .putBoolean(KEY_HIDE_PENDING, hidePending)
            .remove(LEGACY_KEY_FILTER_STATE)
            .apply()
    }

    private fun togglePending() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_HIDE_PENDING, true)
        prefs.edit().putBoolean(KEY_HIDE_PENDING, !current).apply()
        updateActiveFilterIndicator()
        loadHistory()
    }

    private fun clearAllFilters() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_PENDING, true)
            .putLong(KEY_FILTER_DATE_START, 0L)
            .putLong(KEY_FILTER_DATE_END, 0L)
            .apply()
        updateActiveFilterIndicator()
        loadHistory()
    }

    private fun showDateFilterSheet() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentStart = prefs.getLong(KEY_FILTER_DATE_START, 0L)
        val currentEnd = prefs.getLong(KEY_FILTER_DATE_END, 0L)
        DateFilterBottomSheet
            .newInstance(currentStart, currentEnd)
            .show(supportFragmentManager, DateFilterBottomSheet.TAG)
    }

    private fun registerDateFilterResultListener() {
        supportFragmentManager.setFragmentResultListener(
            DateFilterBottomSheet.RESULT_KEY,
            this
        ) { _, bundle ->
            val start = bundle.getLong(DateFilterBottomSheet.RESULT_START, 0L)
            val end = bundle.getLong(DateFilterBottomSheet.RESULT_END, 0L)
            if (start == DateFilterBottomSheet.SENTINEL_CUSTOM) {
                showDateRangePicker()
                return@setFragmentResultListener
            }
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_FILTER_DATE_START, start)
                .putLong(KEY_FILTER_DATE_END, end)
                .apply()
            updateActiveFilterIndicator()
            loadHistory()
        }
    }

    private fun updateActiveFilterIndicator() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hidePending = prefs.getBoolean(KEY_HIDE_PENDING, true)
        val start = prefs.getLong(KEY_FILTER_DATE_START, 0L)
        val end = prefs.getLong(KEY_FILTER_DATE_END, 0L)

        val parts = mutableListOf<String>()
        if (start > 0L && end > 0L) {
            val format = SimpleDateFormat("MMM d", Locale.getDefault())
            parts += getString(
                R.string.history_filter_date_range_format,
                format.format(Date(start)),
                format.format(Date(end))
            )
        }
        if (!hidePending) {
            parts += getString(R.string.history_subtitle_pending_shown)
        }

        val indicator = findViewById<Chip>(R.id.active_filter_indicator)
        if (parts.isEmpty()) {
            indicator.visibility = View.GONE
        } else {
            indicator.text = parts.joinToString(getString(R.string.history_subtitle_separator))
            indicator.visibility = View.VISIBLE
        }
    }

    private fun showDateRangePicker() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentStart = prefs.getLong(KEY_FILTER_DATE_START, 0L)
        val currentEnd = prefs.getLong(KEY_FILTER_DATE_END, 0L)

        // Find the oldest transaction to constrain the picker's start date
        val paymentHistory: List<HistoryEntry> = getPaymentHistory()
        val withdrawHistory: List<HistoryEntry> = AutoWithdrawManager.getInstance(this)
            .getHistory()
            .filter { it.status != WithdrawHistoryEntry.STATUS_FAILED }
        
        val allHistory = paymentHistory + withdrawHistory
        val oldestDate = allHistory.minByOrNull { it.date.time }?.date?.time
        
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        
        // Give a 1-month buffer before the oldest transaction, or default to 2023 if empty
        val startBounds = oldestDate?.let { it - (30L * 24 * 60 * 60 * 1000) } ?: run {
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.set(2023, java.util.Calendar.JANUARY, 1)
            calendar.timeInMillis
        }

        val constraintsBuilder = CalendarConstraints.Builder()
            .setStart(startBounds)
            .setEnd(today)
            .setValidator(DateValidatorPointBackward.now())

        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTheme(R.style.ThemeOverlay_Numo_MaterialCalendar)
            .setTitleText(R.string.history_filter_date_picker_title)
            .setCalendarConstraints(constraintsBuilder.build())

        var validStart = currentStart
        var validEnd = currentEnd

        if (validStart > 0 && validStart < startBounds) {
            validStart = startBounds
        }
        if (validEnd > today) {
            validEnd = today
        }

        if (validStart > 0 && validEnd > 0 && validStart <= validEnd) {
            builder.setSelection(androidx.core.util.Pair(validStart, validEnd))
        }

        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            prefs.edit()
                .putLong(KEY_FILTER_DATE_START, selection.first)
                .putLong(KEY_FILTER_DATE_END, selection.second)
                .apply()
            updateActiveFilterIndicator()
            loadHistory()
        }
        picker.show(supportFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun showOverflowMenu(anchor: View) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val popup = PopupMenu(this, anchor, android.view.Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_activity_history, popup.menu)

        val hidePending = prefs.getBoolean(KEY_HIDE_PENDING, true)
        popup.menu.findItem(R.id.menu_toggle_pending)?.apply {
            title = getString(
                if (hidePending) R.string.history_overflow_show_pending
                else R.string.history_overflow_hide_pending
            )
            setIcon(if (hidePending) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_toggle_pending -> {
                    togglePending()
                    true
                }
                R.id.menu_filter_date -> {
                    showDateFilterSheet()
                    true
                }
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hidePending = prefs.getBoolean(KEY_HIDE_PENDING, true)
        val filterStart = prefs.getLong(KEY_FILTER_DATE_START, 0L)
        val filterEnd = prefs.getLong(KEY_FILTER_DATE_END, 0L)

        val paymentHistory: List<HistoryEntry> = getPaymentHistory()
        val withdrawHistory: List<HistoryEntry> = AutoWithdrawManager.getInstance(this)
            .getHistory()
            .filter { it.status != WithdrawHistoryEntry.STATUS_FAILED }

        // Merge and sort by date descending (newest first)
        var filteredList = (paymentHistory + withdrawHistory)
            .sortedByDescending { it.date.time }

        if (hidePending) {
            filteredList = filteredList.filterNot { it.isPending() }
        }

        // Apply Date Filter
        if (filterStart > 0 && filterEnd > 0) {
            // MaterialDatePicker returns UTC midnights. To include the full end day:
            val endOfDay = filterEnd + 86400000L - 1L
            filteredList = filteredList.filter { it.date.time in filterStart..endOfDay }
        }

        currentHistoryList = filteredList
        adapter.setEntries(currentHistoryList)

        val isEmpty = currentHistoryList.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
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

    private fun getPaymentHistory(): List<PaymentHistoryEntry> = getPaymentHistory(this)

    companion object {
        private const val PREFS_NAME = "PaymentHistory"
        private const val KEY_HISTORY = "history"
        private const val KEY_HIDE_PENDING = "hide_pending"
        private const val KEY_FILTER_DATE_START = "filter_date_start"
        private const val KEY_FILTER_DATE_END = "filter_date_end"
        private const val LEGACY_KEY_FILTER_STATE = "filter_state"
        private const val LEGACY_FILTER_PAID = 1
        private const val REQUEST_TRANSACTION_DETAIL = 1001
        private const val REQUEST_RESUME_PAYMENT = 1002

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
        ) {
            val history = getPaymentHistory(context).toMutableList()
            val index = history.indexOfFirst { it.id == paymentId }

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
                    checkoutBasketJson = existing.checkoutBasketJson, // Preserve basket data
                    basketId = existing.basketId, // Preserve basket ID
                    tipAmountSats = existing.tipAmountSats, // Preserve tip info
                    tipPercentage = existing.tipPercentage, // Preserve tip info
                    label = existing.label, // Preserve label
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
