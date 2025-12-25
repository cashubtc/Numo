package com.electricdreams.numo.feature.settings

import android.app.DatePickerDialog
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
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import com.electricdreams.numo.core.dev.ErrorLogStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Developer-facing screen that displays persisted error logs and allows
 * copying or sharing them for debugging purposes.
 */
class ErrorLogsActivity : AppCompatActivity() {

    private lateinit var adapter: ErrorLogsAdapter
    private lateinit var dateFilterValue: TextView
    private lateinit var emptyView: TextView

    private val calendar: Calendar = Calendar.getInstance()
    private val headerDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val lineDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_logs)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        dateFilterValue = findViewById(R.id.date_filter_value)
        emptyView = findViewById(R.id.empty_view)

        val recyclerView: RecyclerView = findViewById(R.id.error_logs_recycler_view)
        adapter = ErrorLogsAdapter { entry ->
            showEntryDetails(entry)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.date_filter_row).setOnClickListener {
            showDatePicker()
        }

        findViewById<View>(R.id.copy_all_button).setOnClickListener {
            copyAllToClipboard()
        }

        findViewById<View>(R.id.share_button).setOnClickListener {
            shareLogs()
        }

        updateDateLabel()
        loadLogsForCurrentDate()
    }

    private fun showDatePicker() {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(
            this,
            { _, y, m, d ->
                calendar.set(y, m, d, 23, 59, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                updateDateLabel()
                loadLogsForCurrentDate()
            },
            year,
            month,
            day,
        ).show()
    }

    private fun updateDateLabel() {
        val today = Calendar.getInstance()
        if (isSameDay(calendar.time, today.time)) {
            dateFilterValue.setText(R.string.developer_error_logs_filter_today)
        } else {
            dateFilterValue.text = headerDateFormat.format(calendar.time)
        }
    }

    private fun loadLogsForCurrentDate() {
        val endOfDay: Date = calendar.time
        val logs = ErrorLogStore.getErrorsUpTo(endOfDay)
        adapter.submitList(logs)

        val isEmpty = logs.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showEntryDetails(entry: ErrorLogEntry) {
        val fullText = buildString {
            append("Time: ").append(lineDateFormat.format(entry.timestamp)).append('\n')
            append("Tag: ").append(entry.tag).append('\n')
            append("Message: ").append(entry.message)
            if (!entry.stackTrace.isNullOrBlank()) {
                append("\n\nStack trace:\n").append(entry.stackTrace)
            }
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
        Toast.makeText(this, R.string.developer_error_logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val logs = adapter.currentList
        if (logs.isEmpty()) return

        val text = buildLogsText(logs)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.developer_error_logs_share_title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.developer_error_logs_share_title)))
    }

    private fun buildLogsText(logs: List<ErrorLogEntry>): String {
        return logs.joinToString(separator = "\n\n") { entry ->
            buildString {
                append(lineDateFormat.format(entry.timestamp))
                append(" • ")
                append(entry.tag)
                append(" • ")
                append(entry.message)
                if (!entry.stackTrace.isNullOrBlank()) {
                    append("\n").append(entry.stackTrace)
                }
            }
        }
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Error Logs", text))
    }

    private fun isSameDay(d1: Date, d2: Date): Boolean {
        val c1 = Calendar.getInstance().apply { time = d1 }
        val c2 = Calendar.getInstance().apply { time = d2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }
}
