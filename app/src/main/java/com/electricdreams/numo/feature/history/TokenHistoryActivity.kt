package com.electricdreams.numo.feature.history

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import com.electricdreams.numo.ui.util.DialogHelper
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.components.EmptyStateHelper
import com.electricdreams.numo.core.data.model.TokenHistoryEntry
import com.electricdreams.numo.databinding.ActivityHistoryBinding
import com.electricdreams.numo.ui.adapter.TokenHistoryAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.Collections

class TokenHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: TokenHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge so token history list runs behind the nav pill as well
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        adapter = TokenHistoryAdapter().apply {
            setOnDeleteClickListener { entry, position ->
                DialogHelper.showConfirmation(this@TokenHistoryActivity, DialogHelper.ConfirmationConfig(
                    title = getString(R.string.token_history_dialog_delete_title),
                    message = getString(R.string.token_history_dialog_delete_message),
                    confirmText = getString(R.string.token_history_dialog_delete_positive),
                    isDestructive = true,
                    onConfirm = { deleteTokenFromHistory(position) }
                ))
            }
        }

        binding.historyRecyclerView.adapter = adapter
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)

        // Load and display history
        loadHistory()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadHistory() {
        val history = getTokenHistory().toMutableList()
        Collections.reverse(history) // Show newest first
        adapter.setEntries(history)

        val isEmpty = history.isEmpty()
        binding.emptyView.root.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            EmptyStateHelper.bind(
                binding.emptyView.root,
                R.drawable.ic_receipt,
                "No Tokens Yet",
                "Cashu token history will appear here"
            )
        }
    }

    private fun showClearHistoryConfirmation() {
        DialogHelper.showConfirmation(this, DialogHelper.ConfirmationConfig(
            title = getString(R.string.token_history_dialog_clear_title),
            message = getString(R.string.token_history_dialog_clear_message),
            confirmText = getString(R.string.token_history_dialog_clear_positive),
            isDestructive = true,
            onConfirm = { clearAllHistory() }
        ))
    }

    private fun clearAllHistory() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, "[]").apply()
        loadHistory()
    }

    private fun deleteTokenFromHistory(position: Int) {
        val history = getTokenHistory().toMutableList()
        Collections.reverse(history)
        if (position in 0 until history.size) {
            history.removeAt(position)
            Collections.reverse(history)

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()

            loadHistory()
        }
    }

    private fun getTokenHistory(): List<TokenHistoryEntry> = getTokenHistory(this)

    companion object {
        private const val PREFS_NAME = "TokenHistory"
        private const val KEY_HISTORY = "history"

        @JvmStatic
        fun getTokenHistory(context: Context): List<TokenHistoryEntry> {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = prefs.getString(KEY_HISTORY, "[]")
            val type: Type = object : TypeToken<ArrayList<TokenHistoryEntry>>() {}.type
            return Gson().fromJson(json, type)
        }

        @JvmStatic
        fun addToHistory(context: Context, token: String, amount: Long) {
            val history = getTokenHistory(context).toMutableList()
            history.add(TokenHistoryEntry(token, amount, java.util.Date()))

            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, Gson().toJson(history)).apply()
        }
    }
}
