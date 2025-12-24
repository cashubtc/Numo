package com.electricdreams.numo.feature.history

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.TokenHistoryEntry
import com.electricdreams.numo.ui.adapter.TokenHistoryAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.Collections

class TokenHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: TokenHistoryAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // Edge-to-edge so token history list runs behind the nav pill as well
        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        val recyclerView: RecyclerView = findViewById(R.id.history_recycler_view)
        emptyView = findViewById(R.id.empty_view)

        adapter = TokenHistoryAdapter().apply {
            setOnDeleteClickListener { entry, position ->
                AlertDialog.Builder(this@TokenHistoryActivity)
                    .setTitle(R.string.token_history_dialog_delete_title)
                    .setMessage(R.string.token_history_dialog_delete_message)
                    .setPositiveButton(R.string.token_history_dialog_delete_positive) { _, _ -> deleteTokenFromHistory(position) }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
        }

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

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
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.token_history_dialog_clear_title)
            .setMessage(R.string.token_history_dialog_clear_message)
            .setPositiveButton(R.string.token_history_dialog_clear_positive) { _, _ -> clearAllHistory() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
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
