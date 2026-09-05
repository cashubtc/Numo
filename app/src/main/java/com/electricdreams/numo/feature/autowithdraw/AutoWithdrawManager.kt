package com.electricdreams.numo.feature.autowithdraw

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.UnitFeaturePolicy
import com.electricdreams.numo.core.model.UnitId
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.MintManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.cashudevkit.FinalizedMelt
import org.cashudevkit.MintUrl
import org.cashudevkit.QuoteState
import org.cashudevkit.Wallet
import org.cashudevkit.decodeInvoice
import com.electricdreams.numo.core.data.model.HistoryEntry
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.UUID

/**
 * Data class representing a withdrawal history entry (automatic or manual).
 */
data class WithdrawHistoryEntry(
    override val id: String = UUID.randomUUID().toString(),
    override val mintUrl: String,
    // For backwards compatibility: original field storing auto-withdraw lightning address
    val lightningAddress: String? = null,
    // Destination label (Lightning address or abbreviated invoice)
    val destination: String = "",
    // "auto_address", "manual_address", "manual_invoice", etc.
    val destinationType: String = "",
    val amountSats: Long,
    val feeSats: Long,
    override val status: String, // "pending", "completed", "failed"
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val quoteId: String? = null,
    // True for automatic withdrawals, false for manual withdrawals
    val automatic: Boolean = true,
    // The generated Cashu token, if this was a manual token withdrawal
    val token: String? = null,
    // User-assigned label for this transaction
    override val label: String? = null,
    // Lightning accounting above remains in sats; preserve the source wallet denomination too.
    val sourceUnit: String? = null,
    val sourceAmountAtomic: Long? = null,
    val sourceFeeAtomic: Long? = null,
) : HistoryEntry {

    // HistoryEntry computed properties — no backing field, so Gson won't serialize them
    override val amount: Long get() = -amountSats
    override val date: Date get() = Date(timestamp)
    override val enteredAmount: Long get() = amountSats

    override fun getEntryUnit(): String = "sat"
    override fun getBaseAmountSats(): Long = -amountSats
    override fun isPending(): Boolean = status == STATUS_PENDING
    override fun isCompleted(): Boolean = status == STATUS_COMPLETED

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Callback interface for auto-withdrawal progress updates.
 */
interface AutoWithdrawProgressListener {
    fun onWithdrawStarted(mintUrl: String, amount: Long, lightningAddress: String)
    fun onWithdrawProgress(step: String, detail: String)
    fun onWithdrawCompleted(mintUrl: String, amount: Long, fee: Long)
    fun onWithdrawFailed(mintUrl: String, error: String)
}

/**
 * Manages automatic withdrawals when mint balances exceed thresholds.
 * 
 * This manager:
 * - Checks if balances exceed configured thresholds after payments
 * - Executes withdrawals to configured Lightning addresses
 * - Persists withdrawal history and melt quotes in payment history
 * - Provides progress callbacks for UI updates
 */
class AutoWithdrawManager internal constructor(
    private val context: Context,
    private val invoiceAmountMsat: (String) -> ULong? = { decodeInvoice(it).amountMsat },
    private val withdrawalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    companion object {
        private const val TAG = "AutoWithdrawManager"
        private const val PREFS_NAME = "AutoWithdrawHistory" // shared for all withdrawals
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY_ENTRIES = 100

        @Volatile
        private var instance: AutoWithdrawManager? = null

        fun getInstance(context: Context): AutoWithdrawManager {
            return instance ?: synchronized(this) {
                instance ?: AutoWithdrawManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val settingsManager = AutoWithdrawSettingsManager.getInstance(context)
    private val mintManager = MintManager.getInstance(context)
    private val gson = Gson()
    
    private var progressListener: AutoWithdrawProgressListener? = null
    
    @Volatile
    private var isWithdrawInProgress = false
    private val withdrawalMutex = Mutex()
    
    /**
     * Set a progress listener for UI updates.
     */
    fun setProgressListener(listener: AutoWithdrawProgressListener?) {
        progressListener = listener
    }

    /**
     * Check if a withdrawal is currently in progress.
     */
    fun isWithdrawing(): Boolean = isWithdrawInProgress

    /**
     * Called after a successful payment to check if auto-withdrawal should be triggered.
     * This is the main entry point for triggering auto-withdrawals from payment flows.
     * 
     * This method launches the withdrawal check in a background coroutine that survives
     * activity lifecycle changes. The withdrawal will continue even if the calling
     * activity is destroyed.
     * 
     * @param token The Cashu token received (can be empty for Lightning payments)
     * @param lightningMintUrl The mint URL for Lightning payments (used when token is empty)
     */
    fun onPaymentReceived(token: String, lightningMintUrl: String?) {
        onPaymentReceived(token, lightningMintUrl, mintManager.getPreferredUnit())
    }

    /**
     * Unit-explicit entry point used by checkout. Custom units intentionally skip BOLT11-based
     * automatic withdrawal until a payment-method-specific implementation exists.
     */
    fun onPaymentReceived(token: String, lightningMintUrl: String?, paymentUnit: String) {
        val unit = UnitId.ofOrNull(paymentUnit)
        if (unit == null || !UnitFeaturePolicy.supportsAutoWithdraw(unit)) {
            Log.d(TAG, "Auto-withdraw is unavailable for payment unit: $paymentUnit")
            return
        }

        // Determine the mint URL
        val mintUrl: String? = if (token.isNotEmpty()) {
            try {
                org.cashudevkit.Token.decode(token).mintUrl().url
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract mint URL from token: ${e.message}")
                null
            }
        } else {
            lightningMintUrl
        }
        
        Log.d(TAG, "💰 Payment received, checking for auto-withdrawal. mintUrl=$mintUrl")
        
        if (mintUrl == null) {
            Log.w(TAG, "⚠️ No mint URL available, skipping auto-withdrawal check")
            return
        }
        
        // Launch in application-scoped coroutine that survives activity destruction
        withdrawalScope.launch {
            try {
                Log.d(TAG, "🚀 Starting auto-withdrawal check in background scope")
                checkAndTriggerWithdrawals(mintUrl, unit.value)
                Log.d(TAG, "✅ Auto-withdrawal check completed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking auto-withdrawals", e)
            }
        }
    }

    /**
     * Check all mints and trigger withdrawals if needed.
     * Called after a payment is received.
     * 
     * @param paymentMintUrl Optional: the mint that just received payment (checked first)
     */
    suspend fun checkAndTriggerWithdrawals(
        paymentMintUrl: String? = null,
        paymentUnit: String = mintManager.getPreferredUnit(),
    ) {
        Log.d(TAG, "=== checkAndTriggerWithdrawals START ===")
        Log.d(TAG, "paymentMintUrl: $paymentMintUrl")
        
        if (isWithdrawInProgress) {
            Log.d(TAG, "Withdrawal already in progress, skipping check")
            return
        }

        if (!settingsManager.isGloballyEnabled()) {
            Log.d(TAG, "Auto-withdraw is globally disabled, skipping")
            return
        }

        val activeUnit = UnitId.ofOrNull(paymentUnit)
        if (activeUnit == null || !UnitFeaturePolicy.supportsAutoWithdraw(activeUnit)) {
            Log.d(TAG, "Auto-withdraw is unavailable for active unit: $activeUnit")
            return
        }
        
        Log.d(TAG, "Auto-withdraw is globally enabled, checking balances...")

        // Include valuation in the lock so two payments cannot spend the same balance snapshot.
        if (!withdrawalMutex.tryLock()) return
        try {
            // Get all mint balances
            val balances = withContext(Dispatchers.IO) {
                CashuWalletManager.getAllMintBalances(activeUnit.value)
            }
            Log.d(TAG, "Retrieved ${balances.size} mint balances: $balances")
            
            if (balances.isEmpty()) {
                Log.w(TAG, "No mint balances found!")
                return
            }
            
            val repository = CashuWalletManager.getWallet() ?: return
            val paymentMint = paymentMintUrl?.removeSuffix("/")
            val orderedBalances = balances.entries.sortedBy { if (it.key == paymentMint) 0 else 1 }
            for ((mintUrl, balanceAtomic) in orderedBalances) {
                if (balanceAtomic <= 0 || !settingsManager.isEnabledForMint(mintUrl)) continue
                if (settingsManager.getMintSettings(mintUrl).lightningAddress.isBlank()) continue
                try {
                    // Retain the wallet and unit for the entire withdrawal.
                    val mintWallet = withContext(Dispatchers.IO) {
                        repository.getWallet(
                            MintUrl(mintUrl),
                            CashuWalletManager.getCurrencyUnit(activeUnit.value),
                        )
                    }
                    val balanceSats = withContext(Dispatchers.IO) {
                        balanceInSats(mintWallet, balanceAtomic, activeUnit)
                    }
                    if (settingsManager.shouldTriggerWithdrawal(mintUrl, balanceSats)) {
                        executeWithdrawal(mintUrl, balanceAtomic, balanceSats, activeUnit, mintWallet)
                        return
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Could not value $mintUrl balance in ${activeUnit.value}", e)
                }
            }
            
            Log.d(TAG, "No withdrawals triggered for any mint")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error checking balances for auto-withdraw", e)
        } finally {
            withdrawalMutex.unlock()
        }
        
        Log.d(TAG, "=== checkAndTriggerWithdrawals END ===")
    }

    /**
     * Value this wallet in sats for the existing sat-denominated withdrawal settings.
     */
    private suspend fun balanceInSats(wallet: Wallet, balanceAtomic: Long, unit: UnitId): Long {
        return when (unit) {
            UnitId.SAT, UnitId.BTC -> balanceAtomic
            UnitId.MSAT -> balanceAtomic / 1_000L
            else -> {
                // Ask this issuer for a fresh valuation instead of reusing the POS display rate.
                // This invoice is only a quote; no payment is made to it and no proofs are minted.
                val quote = wallet.mintQuote(
                    org.cashudevkit.PaymentMethod.Bolt11,
                    org.cashudevkit.Amount(balanceAtomic.toULong()),
                    null,
                    null,
                )
                require(quote.unit == CashuWalletManager.getCurrencyUnit(unit.value))
                require(quote.amount?.value == balanceAtomic.toULong())
                require(quote.expiry > (System.currentTimeMillis() / 1_000L).toULong()) {
                    "Balance conversion quote has expired"
                }
                val msat = requireNotNull(invoiceAmountMsat(quote.request)) {
                    "Balance conversion invoice has no amount"
                }
                require(msat > 0uL) { "Balance conversion invoice amount must be positive" }
                (msat / 1_000uL).toLongExactAmount()
            }
        }
    }

    private suspend fun executeWithdrawal(
        mintUrl: String,
        currentBalanceAtomic: Long,
        currentBalanceSats: Long,
        paymentUnit: UnitId,
        mintWallet: Wallet,
    ) {
        if (isWithdrawInProgress) {
            Log.w(TAG, "executeWithdrawal called but already in progress, skipping")
            return
        }
        val settings = settingsManager.getMintSettings(mintUrl)
        val withdrawAmount = settingsManager.calculateWithdrawAmount(mintUrl, currentBalanceSats)
        val lightningAddress = settings.lightningAddress

        Log.d(TAG, "🚀 STARTING AUTO-WITHDRAWAL:")
        Log.d(TAG, "   Mint: $mintUrl")
        Log.d(TAG, "   Current balance: $currentBalanceAtomic ${paymentUnit.value}")
        Log.d(TAG, "   Withdraw amount: $withdrawAmount sats (${settings.withdrawPercentage}%)")
        Log.d(TAG, "   Lightning address: $lightningAddress")
        Log.d(TAG, "   Threshold: ${settings.thresholdSats} sats")

        var historyEntry = WithdrawHistoryEntry(
            mintUrl = mintUrl,
            lightningAddress = lightningAddress,
            destination = lightningAddress,
            destinationType = "auto_address",
            amountSats = withdrawAmount,
            feeSats = 0,
            status = WithdrawHistoryEntry.STATUS_PENDING,
            automatic = true,
            sourceUnit = paymentUnit.value,
        )

        isWithdrawInProgress = true
        try {
            withContext(Dispatchers.Main) {
                progressListener?.onWithdrawStarted(mintUrl, withdrawAmount, lightningAddress)
                progressListener?.onWithdrawProgress("Preparing", "Getting quote...")
            }
            // Get melt quote for Lightning address
            Log.d(TAG, "📋 Step 2: Getting melt quote...")
            withContext(Dispatchers.Main) {
                progressListener?.onWithdrawProgress("Quote", "Getting Lightning quote...")
            }

            val amountMsat = Math.multiplyExact(withdrawAmount, 1_000L)
            Log.d(TAG, "   Requesting quote for $withdrawAmount sats ($amountMsat msat) to $lightningAddress")
            
            val meltQuote = withContext(Dispatchers.IO) {
                Log.d(TAG, "   Making CDK call: wallet.meltLightningAddressQuote()")
                try {
                    val quote = mintWallet.meltLightningAddressQuote(lightningAddress, org.cashudevkit.Amount(amountMsat.toULong()))
                    Log.d(TAG, "   ✅ Quote received: id=${quote.id}")
                    quote
                } catch (e: Exception) {
                    Log.e(TAG, "   ❌ Quote failed: ${e.message}", e)
                    throw e
                }
            }

            require(meltQuote.unit == CashuWalletManager.getCurrencyUnit(paymentUnit.value)) {
                "Melt quote has a different unit than the withdrawal wallet"
            }
            val quoteAmount = meltQuote.amount.value.toLongExactAmount()
            require(quoteAmount > 0L) { "Melt quote amount must be positive" }
            val feeReserve = meltQuote.feeReserve.value.toLongExactAmount()
            val totalRequired = Math.addExact(quoteAmount, feeReserve)

            Log.d(TAG, "✅ Melt quote received:")
            Log.d(TAG, "   Quote ID: ${meltQuote.id}")
            Log.d(TAG, "   Amount: $quoteAmount ${paymentUnit.value}")
            Log.d(TAG, "   Fee reserve: $feeReserve ${paymentUnit.value}")
            Log.d(TAG, "   Total required: $totalRequired ${paymentUnit.value}")
            Log.d(TAG, "   Request (BOLT11): ${meltQuote.request}")

            // Check if we have enough balance
            val spendBudget = settingsManager.calculateWithdrawAmount(mintUrl, currentBalanceAtomic)
            if (totalRequired > currentBalanceAtomic || quoteAmount > spendBudget) {
                throw Exception("Melt quote exceeds the withdrawal budget in ${paymentUnit.value}")
            }

            // Update history entry with quote info
            historyEntry = historyEntry.copy(
                quoteId = meltQuote.id,
                feeSats = feeInSats(feeReserve, withdrawAmount, quoteAmount),
                sourceAmountAtomic = quoteAmount,
                sourceFeeAtomic = feeReserve,
            )

            // Execute melt using simplified API
            Log.d(TAG, "📋 Step 4: Executing melt operation...")
            withContext(Dispatchers.Main) {
                progressListener?.onWithdrawProgress("Sending", "Sending payment...")
            }

            val finalized: FinalizedMelt = withContext(Dispatchers.IO) {
                Log.d(TAG, "   Making CDK call: wallet.prepareMelt() + confirm()")
                try {
                    val prepared = mintWallet.prepareMelt(meltQuote.id)
                    val result = prepared.confirm()
                    Log.d(TAG, "   Melt confirm returned: state=${result.state}, feePaid=${result.feePaid.value}, preimage=${result.preimage != null}")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "   Melt failed: ${e.message}", e)
                    throw e
                }
            }

            // Check melt state
            Log.d(TAG, "📋 Step 5: Checking melt result state...")
            val actualFeeAtomic = finalized.feePaid.value.toLongExactAmount()
            val actualFee = feeInSats(actualFeeAtomic, withdrawAmount, quoteAmount)

            when (finalized.state) {
                QuoteState.PAID -> {
                    Log.d(TAG, "🎉 AUTO-WITHDRAWAL SUCCESSFUL!")
                    Log.d(TAG, "   Amount withdrawn: $withdrawAmount sats")
                    Log.d(TAG, "   Fee paid: $actualFee sats (reserved: $feeReserve)")
                    Log.d(TAG, "   Lightning address: $lightningAddress")
                    
                    historyEntry = historyEntry.copy(
                        status = WithdrawHistoryEntry.STATUS_COMPLETED,
                        feeSats = actualFee,
                        sourceFeeAtomic = actualFeeAtomic,
                    )
                    
                    // Broadcast balance change so other activities can refresh
                    BalanceRefreshBroadcast.send(context, BalanceRefreshBroadcast.REASON_AUTO_WITHDRAWAL)
                    
                    withContext(Dispatchers.Main) {
                        progressListener?.onWithdrawCompleted(mintUrl, withdrawAmount, actualFee)
                    }
                }
                QuoteState.PENDING -> {
                    Log.d(TAG, "⏳ Auto-withdrawal pending (waiting for Lightning payment)")
                    historyEntry = historyEntry.copy(
                        status = WithdrawHistoryEntry.STATUS_PENDING,
                        errorMessage = "Payment pending - check back later"
                    )
                    withContext(Dispatchers.Main) {
                        progressListener?.onWithdrawProgress("Pending", "Payment is pending...")
                    }
                }
                QuoteState.UNPAID -> {
                    Log.e(TAG, "❌ Auto-withdrawal failed: Quote is UNPAID")
                    throw Exception("Payment failed: Quote state is UNPAID")
                }
                else -> {
                    Log.e(TAG, "❌ Auto-withdrawal failed: Unknown quote state ${finalized.state}")
                    throw Exception("Payment failed: Unknown quote state ${finalized.state}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 AUTO-WITHDRAWAL FAILED:")
            Log.e(TAG, "   Mint: $mintUrl")
            Log.e(TAG, "   Amount: $withdrawAmount sats")
            Log.e(TAG, "   Error: ${e.message}")
            Log.e(TAG, "   Exception type: ${e.javaClass.simpleName}")
            
            // If it's a JobCancellationException, log that specifically
            if (e is kotlinx.coroutines.CancellationException) {
                Log.e(TAG, "   🚫 Withdrawal was cancelled (likely due to scope cancellation)")
            }
            
            historyEntry = historyEntry.copy(
                status = WithdrawHistoryEntry.STATUS_FAILED,
                errorMessage = e.message
            )
            withContext(Dispatchers.Main) {
                progressListener?.onWithdrawFailed(mintUrl, e.message ?: "Unknown error")
            }
        } finally {
            Log.d(TAG, "🏁 Withdrawal finished, saving to history...")
            // Save to auto-withdraw history
            addToHistory(historyEntry)
            isWithdrawInProgress = false
            Log.d(TAG, "🏁 Auto-withdrawal process completed")
        }
    }

    private fun ULong.toLongExactAmount(): Long {
        require(this <= Long.MAX_VALUE.toULong()) { "Amount exceeds Long.MAX_VALUE" }
        return toLong()
    }

    private fun feeInSats(feeAtomic: Long, paymentSats: Long, quoteAtomic: Long): Long =
        BigDecimal.valueOf(feeAtomic)
            .multiply(BigDecimal.valueOf(paymentSats))
            .divide(BigDecimal.valueOf(quoteAtomic), 0, RoundingMode.CEILING)
            .longValueExact()

    /**
     * Get auto-withdraw history.
     */
    fun getHistory(): List<WithdrawHistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WithdrawHistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading auto-withdraw history", e)
            emptyList()
        }
    }

    /**
     * Add entry to auto-withdraw history.
     */
    private fun addToHistory(entry: WithdrawHistoryEntry) {
        val history = getHistory().toMutableList()
        history.add(0, entry) // Add at beginning (newest first)
        
        // Limit history size
        while (history.size > MAX_HISTORY_ENTRIES) {
            history.removeAt(history.size - 1)
        }
        
        saveHistory(history)
    }

    /**
     * Save auto-withdraw history.
     */
    private fun saveHistory(history: List<WithdrawHistoryEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    /**
     * Clear auto-withdraw history.
     */
    fun clearHistory() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * Add a manual withdrawal entry to the unified withdrawal history.
     */
    fun addManualWithdrawalEntry(
        mintUrl: String,
        amountSats: Long,
        feeSats: Long,
        destination: String,
        destinationType: String,
        status: String,
        quoteId: String? = null,
        errorMessage: String? = null,
        token: String? = null
    ): WithdrawHistoryEntry {
        val entry = WithdrawHistoryEntry(
            mintUrl = mintUrl,
            lightningAddress = if (destinationType.contains("address")) destination else null,
            destination = destination,
            destinationType = destinationType,
            amountSats = amountSats,
            feeSats = feeSats,
            status = status,
            quoteId = quoteId,
            errorMessage = errorMessage,
            automatic = false,
            token = token
        )
        addToHistory(entry)
        return entry
    }

    /**
     * Update the status (and optional error message) of a withdrawal entry.
     */
    fun updateWithdrawalStatus(id: String, status: String, errorMessage: String? = null, feeSats: Long? = null) {
        val history = getHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index >= 0) {
            val existing = history[index]
            val updated = existing.copy(
                status = status,
                errorMessage = errorMessage ?: existing.errorMessage,
                feeSats = feeSats ?: existing.feeSats
            )
            history[index] = updated
            saveHistory(history)
        }
    }

    /**
     * Delete a withdrawal history entry.
     */
    fun deleteHistoryEntry(id: String) {
        val history = getHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index >= 0) {
            history.removeAt(index)
            saveHistory(history)
        }
    }

    /**
     * Update the label on a withdrawal history entry.
     */
    fun updateWithdrawalLabel(id: String, label: String?) {
        val history = getHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index >= 0) {
            history[index] = history[index].copy(label = label?.ifBlank { null })
            saveHistory(history)
        }
    }
}
