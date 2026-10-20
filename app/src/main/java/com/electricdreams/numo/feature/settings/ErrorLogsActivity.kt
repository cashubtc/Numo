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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.ErrorLogEntry
import com.electricdreams.numo.core.dev.ErrorLogStore
import com.electricdreams.numo.core.dev.ErrorLogCollector
import com.electricdreams.numo.core.dev.ErrorLogCollectionState
import com.electricdreams.numo.databinding.ActivityErrorLogsBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets
import kotlinx.coroutines.launch

/**
 * Developer-facing screen that displays persisted error logs and allows
 * copying or sharing them for debugging purposes.
 */
class ErrorLogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErrorLogsBinding

    private lateinit var adapter: ErrorLogsAdapter
    private lateinit var dateFilterValue: TextView
    private lateinit var emptyView: TextView

    private val calendar: Calendar = Calendar.getInstance()
    private var followToday = true
    private var allLogs: List<ErrorLogEntry> = emptyList()
    private val headerDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val lineDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)
        if (savedInstanceState != null) {
            calendar.timeInMillis = savedInstanceState.getLong(KEY_FILTER_DATE, calendar.timeInMillis)
            followToday = savedInstanceState.getBoolean(KEY_FOLLOW_TODAY, true)
        }

        binding.topBar.onNavClick { finish() }

        dateFilterValue = binding.dateFilterValue
        emptyView = binding.emptyView

        val recyclerView: RecyclerView = binding.errorLogsRecyclerView
        adapter = ErrorLogsAdapter { entry ->
            showEntryDetails(entry)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        binding.dateFilterRow.setOnClickListener {
            showDatePicker()
        }

        binding.copyAllButton.setOnClickListener {
            copyAllToClipboard()
        }

        binding.shareButton.setOnClickListener {
            shareLogs()
        }

        showLogsForCurrentDate()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ErrorLogStore.observeErrors().collect { logs ->
                        allLogs = logs
                        showLogsForCurrentDate()
                    }
                }
                launch {
                    ErrorLogCollector.state.collect { state ->
                        binding.collectionStatus.visibility = if (
                            state == ErrorLogCollectionState.COLLECTING
                        ) View.GONE else View.VISIBLE
                        binding.collectionStatus.setText(when (state) {
                            ErrorLogCollectionState.RETRYING -> R.string.developer_error_logs_retrying
                            ErrorLogCollectionState.STOPPED -> R.string.developer_error_logs_disabled
                            else -> R.string.developer_error_logs_starting
                        })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (DeveloperPrefs.isDeveloperModeEnabled(this)) ErrorLogCollector.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_FILTER_DATE, calendar.timeInMillis)
        outState.putBoolean(KEY_FOLLOW_TODAY, followToday)
        super.onSaveInstanceState(outState)
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
                followToday = isSameDay(calendar.time, Date())
                showLogsForCurrentDate()
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

    private fun showLogsForCurrentDate() {
        if (followToday) calendar.timeInMillis = System.currentTimeMillis()
        updateDateLabel()
        val endOfDay = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val logs = allLogs.filter { it.timestamp.time <= endOfDay }
        adapter.submitList(logs)

        val isEmpty = logs.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.copyAllButton.isEnabled = !isEmpty
        binding.shareButton.isEnabled = !isEmpty
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

    companion object {
        private const val KEY_FILTER_DATE = "error_log_filter_date"
        private const val KEY_FOLLOW_TODAY = "error_log_follow_today"
    }
}
