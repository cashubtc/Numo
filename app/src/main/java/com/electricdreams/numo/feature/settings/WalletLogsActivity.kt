package com.electricdreams.numo.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.WalletLogEntry
import com.electricdreams.numo.core.dev.WalletLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Developer-facing screen that displays persisted wallet activity logs and allows
 * copying or sharing them for debugging purposes.
 */
class WalletLogsActivity : AppCompatActivity() {

    private lateinit var adapter: WalletLogsAdapter
    private lateinit var emptyView: TextView

    private val lineDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet_logs)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<com.electricdreams.numo.ui.components.NumoTopBar>(R.id.top_bar).onNavClick { finish() }

        emptyView = findViewById(R.id.empty_view)

        val recyclerView: RecyclerView = findViewById(R.id.wallet_logs_recycler_view)
        adapter = WalletLogsAdapter { entry ->
            showEntryDetails(entry)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.copy_all_button).setOnClickListener {
            copyAllToClipboard()
        }

        findViewById<View>(R.id.share_button).setOnClickListener {
            shareLogs()
        }

        findViewById<View>(R.id.clear_button).setOnClickListener {
            showClearLogsDialog()
        }

        loadLogs()
    }

    private fun loadLogs() {
        val logs = WalletLogStore.getAllEntries()
        adapter.submitList(logs)

        val isEmpty = logs.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showEntryDetails(entry: WalletLogEntry) {
        val fullText = buildString {
            append("Time: ").append(lineDateFormat.format(entry.timestamp)).append('\n')
            append("Direction: ").append(entry.direction).append('\n')
            append("Amount: ").append(entry.amount).append(" sats\n")
            append("Mint: ").append(entry.mintUrl).append('\n')
            append("Message: ").append(entry.message)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.developer_error_logs_entry_details_title)
            .setMessage(fullText)
            .setPositiveButton(R.string.developer_error_logs_entry_copy) { _, _ ->
                copyTextToClipboard(fullText)
            }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    private fun copyAllToClipboard() {
        val logs = adapter.currentList
        if (logs.isEmpty()) return

        val text = buildLogsText(logs)
        copyTextToClipboard(text)
        Toast.makeText(this, R.string.developer_wallet_logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val logs = adapter.currentList
        if (logs.isEmpty()) return

        val text = buildLogsText(logs)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.developer_wallet_logs_share_title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.developer_wallet_logs_share_title)))
    }

    private fun showClearLogsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.developer_wallet_logs_clear_dialog_title)
            .setMessage(R.string.developer_wallet_logs_clear_dialog_message)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                WalletLogStore.clearAll()
                loadLogs()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun buildLogsText(logs: List<WalletLogEntry>): String {
        return logs.joinToString(separator = "\n\n") { entry ->
            buildString {
                append(lineDateFormat.format(entry.timestamp))
                append(" • ")
                append(entry.direction)
                append(" • ")
                append(entry.amount).append(" sats")
                append(" • ")
                append(entry.mintUrl)
                append(" • ")
                append(entry.message)
            }
        }
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Wallet Activity Logs", text))
    }
}
