package com.electricdreams.numo.feature.autowithdraw

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.MintManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.FinalizedMelt
import org.cashudevkit.MintUrl
import org.cashudevkit.QuoteState
import java.util.Date
import java.util.UUID

/**
 * Data class representing a withdrawal history entry (automatic or manual).
 */
data class WithdrawHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val mintUrl: String,
    // For backwards compatibility: original field storing auto-withdraw lightning address
    val lightningAddress: String? = null,
    // Destination label (Lightning address or abbreviated invoice)
    val destination: String = "",
    // "auto_address", "manual_address", "manual_invoice", etc.
    val destinationType: String = "",
    val amountSats: Long,
    val feeSats: Long,
    val status: String, // "pending", "completed", "failed"
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val quoteId: String? = null,
    // True for automatic withdrawals, false for manual withdrawals
    val automatic: Boolean = true
) {
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
class AutoWithdrawManager private constructor(private val context: Context) {

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
    
    /**
     * Application-scoped coroutine scope for background withdrawal operations.
     * Uses SupervisorJob so individual withdrawal failures don't cancel the scope.
     * This scope survives activity lifecycle changes.
     */
    private val withdrawalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        // Determine the mint URL
        val mintUrl: String? = if (token.isNotEmpty()) {
            try {
                com.cashujdk.nut00.Token.decode(token).mint
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
                checkAndTriggerWithdrawals(mintUrl)
                Log.d(TAG, "✅ Auto-withdrawal check completed")
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
    suspend fun checkAndTriggerWithdrawals(paymentMintUrl: String? = null) {
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
        
        Log.d(TAG, "Auto-withdraw is globally enabled, checking balances...")

        try {
            // Get all mint balances
            val balances = CashuWalletManager.getAllMintBalances()
            Log.d(TAG, "Retrieved ${balances.size} mint balances: $balances")
            
            if (balances.isEmpty()) {
                Log.w(TAG, "No mint balances found!")
                return
            }
            
            // Check payment mint first if specified
            if (paymentMintUrl != null) {
                Log.d(TAG, "Checking payment mint first: $paymentMintUrl")
                if (balances.containsKey(paymentMintUrl)) {
                    val balance = balances[paymentMintUrl] ?: 0L
                    Log.d(TAG, "Payment mint balance: $balance sats")
                    if (settingsManager.shouldTriggerWithdrawal(paymentMintUrl, balance)) {
                        Log.d(TAG, ">>> Triggering withdrawal for payment mint!")
                        executeWithdrawal(paymentMintUrl, balance)
                        return // Only process one withdrawal at a time
                    } else {
                        Log.d(TAG, "Payment mint did not trigger withdrawal")
                    }
                } else {
                    Log.w(TAG, "Payment mint URL not found in balances! Available mints: ${balances.keys}")
                }
            }

            // Check other mints
            Log.d(TAG, "Checking other mints...")
            for ((mintUrl, balance) in balances) {
                if (mintUrl == paymentMintUrl) {
                    Log.d(TAG, "Skipping $mintUrl (already checked as payment mint)")
                    continue
                }
                Log.d(TAG, "Checking mint: $mintUrl with balance: $balance")
                if (settingsManager.shouldTriggerWithdrawal(mintUrl, balance)) {
                    Log.d(TAG, ">>> Triggering withdrawal for mint: $mintUrl")
                    executeWithdrawal(mintUrl, balance)
                    return // Only process one withdrawal at a time
                }
            }
            
            Log.d(TAG, "No withdrawals triggered for any mint")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking balances for auto-withdraw", e)
        }
        
        Log.d(TAG, "=== checkAndTriggerWithdrawals END ===")
    }

    /**
     * Execute a withdrawal for a specific mint.
     */
    private suspend fun executeWithdrawal(mintUrl: String, currentBalance: Long) {
        if (isWithdrawInProgress) {
            Log.w(TAG, "executeWithdrawal called but already in progress, skipping")
            return
        }
        isWithdrawInProgress = true

        val settings = settingsManager.getMintSettings(mintUrl)
        val withdrawAmount = settingsManager.calculateWithdrawAmount(mintUrl, currentBalance)
        val lightningAddress = settings.lightningAddress

        Log.d(TAG, "🚀 STARTING AUTO-WITHDRAWAL:")
        Log.d(TAG, "   Mint: $mintUrl")
        Log.d(TAG, "   Current balance: $currentBalance sats")
        Log.d(TAG, "   Withdraw amount: $withdrawAmount sats (${settings.withdrawPercentage}%)")
        Log.d(TAG, "   Lightning address: $lightningAddress")
        Log.d(TAG, "   Threshold: ${settings.thresholdSats} sats")

        withContext(Dispatchers.Main) {
            progressListener?.onWithdrawStarted(mintUrl, withdrawAmount, lightningAddress)
            progressListener?.onWithdrawProgress("Preparing", "Getting quote...")
        }

        var historyEntry = WithdrawHistoryEntry(
            mintUrl = mintUrl,
            lightningAddress = lightningAddress,
            destination = lightningAddress,
            destinationType = "auto_address",
            amountSats = withdrawAmount,
            feeSats = 0,
            status = WithdrawHistoryEntry.STATUS_PENDING,
            automatic = true
        )

        try {
            Log.d(TAG, "📋 Step 1: Getting wallet instance...")
            val wallet = CashuWalletManager.getWallet()
            if (wallet == null) {
                throw Exception("Wallet not initialized")
            }
            Log.d(TAG, "✅ Wallet instance obtained")

            // Get melt quote for Lightning address
            Log.d(TAG, "📋 Step 2: Getting melt quote...")
            withContext(Dispatchers.Main) {
                progressListener?.onWithdrawProgress("Quote", "Getting Lightning quote...")
            }

            val amountMsat = withdrawAmount * 1000
            Log.d(TAG, "   Requesting quote for $withdrawAmount sats ($amountMsat msat) to $lightningAddress")
            
            // Get the wallet for this mint first
            val mintWallet = wallet.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
                ?: throw Exception("Failed to get wallet for mint: $mintUrl")
            
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

            val quoteAmount = meltQuote.amount.value.toLong()
            val feeReserve = meltQuote.feeReserve.value.toLong()
            val totalRequired = quoteAmount + feeReserve

            Log.d(TAG, "✅ Melt quote received:")
            Log.d(TAG, "   Quote ID: ${meltQuote.id}")
            Log.d(TAG, "   Amount: $quoteAmount sats")
            Log.d(TAG, "   Fee reserve: $feeReserve sats")
            Log.d(TAG, "   Total required: $totalRequired sats")
            Log.d(TAG, "   Request (BOLT11): ${meltQuote.request}")

            // Check if we have enough balance
            if (totalRequired > currentBalance) {
                Log.e(TAG, "❌ Insufficient balance: need $totalRequired sats, have $currentBalance sats")
                throw Exception("Insufficient balance for withdrawal + fees (need $totalRequired, have $currentBalance)")
            }
            Log.d(TAG, "✅ Balance check passed: $currentBalance >= $totalRequired")

            // Update history entry with quote info
            historyEntry = historyEntry.copy(
                quoteId = meltQuote.id,
                feeSats = feeReserve
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
            val actualFee = finalized.feePaid.value.toLong()

            when (finalized.state) {
                QuoteState.PAID -> {
                    Log.d(TAG, "🎉 AUTO-WITHDRAWAL SUCCESSFUL!")
                    Log.d(TAG, "   Amount withdrawn: $withdrawAmount sats")
                    Log.d(TAG, "   Fee paid: $actualFee sats (reserved: $feeReserve)")
                    Log.d(TAG, "   Lightning address: $lightningAddress")
                    
                    historyEntry = historyEntry.copy(
                        status = WithdrawHistoryEntry.STATUS_COMPLETED,
                        feeSats = actualFee
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
        errorMessage: String? = null
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
            automatic = false
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
}
