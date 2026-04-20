package com.electricdreams.numo.core.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log

/**
 * Lightweight broadcast mechanism for triggering balance refreshes across activities.
 * 
 * This allows any activity that modifies balances (withdrawal, mint add/remove) to
 * notify other activities that they should reload their balance data.
 * 
 * Usage:
 * - Call BalanceRefreshBroadcast.send(context) after balance-affecting operations
 * - Register receiver in onResume() of activities that display balances
 * - Unregister in onPause()
 */
object BalanceRefreshBroadcast {
    
    private const val TAG = "BalanceRefresh"
    const val ACTION_BALANCE_CHANGED = "com.electricdreams.numo.BALANCE_CHANGED"
    const val EXTRA_REASON = "reason"
    
    // Reasons for balance change (for debugging/logging)
    const val REASON_WITHDRAWAL = "withdrawal"
    const val REASON_MINT_ADDED = "mint_added"
    const val REASON_MINT_REMOVED = "mint_removed"
    const val REASON_MINT_RESET = "mint_reset"
    const val REASON_AUTO_WITHDRAWAL = "auto_withdrawal"
    const val REASON_PAYMENT_RECEIVED = "payment_received"
    const val REASON_LIGHTNING_MINT_CHANGED = "lightning_mint_changed"
    
    /**
     * Send a broadcast to notify all listeners that balances may have changed.
     * 
     * @param context Context for sending the broadcast
     * @param reason Optional reason for the balance change (for debugging)
     */
    fun send(context: Context, reason: String = "") {
        val intent = Intent(ACTION_BALANCE_CHANGED).apply {
            if (reason.isNotEmpty()) {
                putExtra(EXTRA_REASON, reason)
            }
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        Log.d(TAG, "📢 Balance refresh broadcast sent (reason: $reason)")
    }
    
    /**
     * Create a BroadcastReceiver that calls the provided callback when balances change.
     * 
     * @param onBalanceChanged Callback to invoke when balance change is broadcast
     * @return BroadcastReceiver to register/unregister
     */
    fun createReceiver(onBalanceChanged: (reason: String) -> Unit): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BALANCE_CHANGED) {
                    val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
                    Log.d(TAG, "📥 Balance refresh received (reason: $reason)")
                    onBalanceChanged(reason)
                }
            }
        }
    }
    
    /**
     * Get the IntentFilter for registering the receiver.
     */
    fun getIntentFilter(): IntentFilter {
        return IntentFilter(ACTION_BALANCE_CHANGED)
    }
    
    /**
     * Register a receiver with LocalBroadcastManager.
     */
    fun register(context: Context, receiver: BroadcastReceiver) {
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, getIntentFilter())
    }
    
    /**
     * Unregister a receiver from LocalBroadcastManager.
     */
    fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
