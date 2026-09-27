package com.electricdreams.numo.ui.adapter

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.HistoryEntry
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.ui.util.TransactionDates
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PaymentsHistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun interface OnItemClickListener {
        fun onItemClick(entry: HistoryEntry, position: Int)
    }

    fun interface OnItemDeleteListener {
        fun onItemDelete(entry: HistoryEntry, position: Int)
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    /** Sealed class representing either a month header or a transaction item. */
    sealed class ListItem {
        data class Header(val monthLabel: String) : ListItem()
        data class Transaction(
            val entry: HistoryEntry,
            val originalPosition: Int,
            /** What was sold, as Sales names it; null for a withdrawal */
            val title: String?,
        ) : ListItem()
    }

    private val items: MutableList<ListItem> = mutableListOf()
    private val monthYearKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())

    private var onItemClickListener: OnItemClickListener? = null
    private var onItemDeleteListener: OnItemDeleteListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener) {
        onItemClickListener = listener
    }

    fun setOnItemDeleteListener(listener: OnItemDeleteListener) {
        onItemDeleteListener = listener
    }

    /**
     * Groups entries by month and builds a flat list of headers + items. [titles] names each
     * sale by payment id, the way the Sales list does.
     */
    fun setEntries(newEntries: List<HistoryEntry>, titles: Map<String, String> = emptyMap()) {
        val oldItems = ArrayList(items)

        val newItems = mutableListOf<ListItem>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val calendar = Calendar.getInstance()
        val locale = Locale.getDefault()
        val monthFormat = SimpleDateFormat("MMMM", locale)
        val monthYearFormat = SimpleDateFormat("MMMM yyyy", locale)
        var lastMonthKey = ""

        newEntries.forEachIndexed { index, entry ->
            val monthKey = monthYearKeyFormat.format(entry.date)

            if (monthKey != lastMonthKey) {
                // The year only when it isn't this one, so last September isn't read as this one
                calendar.time = entry.date
                val format = if (calendar.get(Calendar.YEAR) == currentYear) monthFormat else monthYearFormat
                newItems.add(ListItem.Header(format.format(entry.date)))
                lastMonthKey = monthKey
            }

            newItems.add(ListItem.Transaction(entry, index, titles[entry.id]))
        }

        val diffResult = DiffUtil.calculateDiff(ListItemDiffCallback(oldItems, newItems))
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    private class ListItemDiffCallback(
        private val oldList: List<ListItem>,
        private val newList: List<ListItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            return when {
                old is ListItem.Header && new is ListItem.Header -> old.monthLabel == new.monthLabel
                old is ListItem.Transaction && new is ListItem.Transaction -> old.entry.id == new.entry.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            return when {
                old is ListItem.Header && new is ListItem.Header -> old == new
                old is ListItem.Transaction && new is ListItem.Transaction ->
                    old.entry.id == new.entry.id &&
                    old.entry.amount == new.entry.amount &&
                    old.entry.status == new.entry.status &&
                    old.entry.label == new.entry.label &&
                    old.title == new.title &&
                    old.originalPosition == new.originalPosition
                else -> false
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> VIEW_TYPE_HEADER
        is ListItem.Transaction -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_history_month_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_payment_history, parent, false)
                TransactionViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Transaction -> (holder as TransactionViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    // ── ViewHolders ──────────────────────────────────────────────────

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.month_header_text)

        fun bind(item: ListItem.Header) {
            text.text = item.monthLabel
        }
    }

    inner class TransactionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mainContent: View = view.findViewById(R.id.main_content)

        val amountText: TextView = view.findViewById(R.id.amount_text)
        val dateText: TextView = view.findViewById(R.id.date_text)
        val titleText: TextView = view.findViewById(R.id.title_text)
        val subtitleText: TextView = view.findViewById(R.id.subtitle_text)
        val statusText: TextView = view.findViewById(R.id.status_text)
        val icon: ImageView = view.findViewById(R.id.icon)
        val statusBadge: FrameLayout = view.findViewById(R.id.status_badge)
        val statusBadgeIcon: ImageView = view.findViewById(R.id.status_badge_icon)

        fun bind(item: ListItem.Transaction) {
            val entry = item.entry
            val context = itemView.context
            val isPending = entry.isPending()
            val isExpired = entry.isExpired()
            val isFailed = entry.isFailed()
            val isIncoming = entry.amount >= 0

            // Every row opens its details; long-press offers Delete, as a menu on the row
            mainContent.setOnClickListener {
                onItemClickListener?.onItemClick(entry, item.originalPosition)
            }
            mainContent.setOnLongClickListener {
                showRowMenu(entry, item.originalPosition)
                true
            }

            // ── Amount display ──
            val entryUnit = entry.getEntryUnit()
            val lowerEntryUnit = entryUnit.lowercase()
            val isCustomUnit = lowerEntryUnit != "sat"

            val formattedAmount = if (isCustomUnit) {
                val currency = Amount.Currency.fromCode(lowerEntryUnit)
                if (currency.symbol != lowerEntryUnit.uppercase()) {
                    Amount(kotlin.math.abs(entry.enteredAmount), currency).toString()
                } else {
                    val formattedNum = Amount(kotlin.math.abs(entry.enteredAmount), currency).toStringWithoutSymbol()
                    "$formattedNum ${entryUnit.uppercase()}"
                }
            } else {
                val baseAmountSats = kotlin.math.abs(entry.getBaseAmountSats())
                val satAmount = Amount(baseAmountSats, Amount.Currency.BTC)
                satAmount.toString()
            }

            // Sales read as plain amounts, as in Sales and the details; only money out is signed
            amountText.text = if (isIncoming) formattedAmount else "-$formattedAmount"

            // ── Date ──
            dateText.text = TransactionDates.row(context, entry.date)

            // ── Title: what was sold, as Sales names it; the status sits under the amount ──
            titleText.text = when {
                !isIncoming -> context.getString(R.string.history_row_title_withdrawal)
                else -> item.title ?: context.getString(R.string.insights_quick_charge)
            }

            // ── Direction icon ──
            icon.setImageResource(
                if (isIncoming) R.drawable.ic_arrow_down_receive
                else R.drawable.ic_arrow_up_send
            )
            icon.setColorFilter(context.getColor(R.color.color_text_primary))

            // ── Status badge: only while something is left to do or went wrong ──
            when {
                isPending -> {
                    statusBadge.setBackgroundResource(R.drawable.bg_status_badge_orange)
                    statusBadgeIcon.setImageResource(R.drawable.ic_clock_small)
                    statusBadge.visibility = View.VISIBLE
                }
                isExpired || isFailed -> {
                    statusBadge.setBackgroundResource(R.drawable.bg_status_badge_red)
                    statusBadgeIcon.setImageResource(R.drawable.ic_clock_small)
                    statusBadge.visibility = View.VISIBLE
                }
                else -> statusBadge.visibility = View.GONE
            }

            // ── Status text (pending/expired/failed) ──
            when {
                isPending -> {
                    statusText.visibility = View.VISIBLE
                    statusText.text = context.getString(R.string.history_row_status_tap_to_resume)
                    statusText.setTextColor(context.getColor(R.color.color_warning))
                }
                isExpired -> {
                    statusText.visibility = View.VISIBLE
                    statusText.text = context.getString(R.string.history_row_status_expired)
                    statusText.setTextColor(context.getColor(R.color.color_error))
                }
                isFailed -> {
                    statusText.visibility = View.VISIBLE
                    statusText.text = context.getString(R.string.history_row_status_failed)
                    statusText.setTextColor(context.getColor(R.color.color_error))
                }
                else -> {
                    statusText.visibility = View.GONE
                }
            }

            // ── Label subtitle ──
            if (!entry.label.isNullOrBlank()) {
                subtitleText.text = entry.label
                subtitleText.visibility = View.VISIBLE
            } else {
                subtitleText.visibility = View.GONE
            }
        }

        private fun showRowMenu(entry: HistoryEntry, position: Int) {
            val context = itemView.context
            val popup = PopupMenu(context, mainContent, Gravity.END)
            val delete = SpannableString(context.getString(R.string.common_delete)).apply {
                setSpan(
                    ForegroundColorSpan(context.getColor(R.color.color_error)),
                    0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            popup.menu.add(delete).setOnMenuItemClickListener {
                onItemDeleteListener?.onItemDelete(entry, position)
                true
            }
            popup.show()
        }
    }
}
