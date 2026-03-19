package com.electricdreams.numo.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.data.model.HistoryEntry
import com.electricdreams.numo.core.model.Amount
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
        data class Transaction(val entry: HistoryEntry, val originalPosition: Int) : ListItem()
    }

    private val items: MutableList<ListItem> = mutableListOf()
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    private val monthYearFormat = SimpleDateFormat("MMMM", Locale.getDefault())
    private val monthYearKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    
    private var onItemClickListener: OnItemClickListener? = null
    private var onItemDeleteListener: OnItemDeleteListener? = null
    
    private var openItemPosition: Int = RecyclerView.NO_POSITION

    fun setOnItemClickListener(listener: OnItemClickListener) {
        onItemClickListener = listener
    }

    fun setOnItemDeleteListener(listener: OnItemDeleteListener) {
        onItemDeleteListener = listener
    }

    /**
     * Groups entries by month and builds a flat list of headers + items.
     */
    fun setEntries(newEntries: List<HistoryEntry>) {
        items.clear()
        openItemPosition = RecyclerView.NO_POSITION

        val calendar = Calendar.getInstance()
        var lastMonthKey = ""

        newEntries.forEachIndexed { index, entry ->
            calendar.time = entry.date
            val monthKey = monthYearKeyFormat.format(entry.date)

            if (monthKey != lastMonthKey) {
                val monthLabel = monthYearFormat.format(entry.date)
                items.add(ListItem.Header(monthLabel))
                lastMonthKey = monthKey
            }

            items.add(ListItem.Transaction(entry, index))
        }

        notifyDataSetChanged()
    }

    fun closeOpenItem() {
        val previousOpen = openItemPosition
        if (previousOpen != RecyclerView.NO_POSITION) {
            openItemPosition = RecyclerView.NO_POSITION
            notifyItemChanged(previousOpen, "close")
        }
    }

    private fun getDeleteWidth(context: Context): Float {
        return 80f * context.resources.displayMetrics.density
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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("close") && holder is TransactionViewHolder) {
            holder.mainContent.animate()
                .translationX(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Transaction -> (holder as TransactionViewHolder).bind(item, position)
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
        val deleteButtonContainer: View = view.findViewById(R.id.delete_button_container)
        
        val amountText: TextView = view.findViewById(R.id.amount_text)
        val dateText: TextView = view.findViewById(R.id.date_text)
        val titleText: TextView = view.findViewById(R.id.title_text)
        val subtitleText: TextView = view.findViewById(R.id.subtitle_text)
        val statusText: TextView = view.findViewById(R.id.status_text)
        val icon: ImageView = view.findViewById(R.id.icon)
        val statusBadge: FrameLayout = view.findViewById(R.id.status_badge)
        val statusBadgeIcon: ImageView = view.findViewById(R.id.status_badge_icon)

        fun bind(item: ListItem.Transaction, position: Int) {
            val entry = item.entry
            val context = itemView.context
            val isPending = entry.isPending()

            // Reset translation immediately without animation to prevent recycled views from staying open
            mainContent.translationX = if (position == openItemPosition) -getDeleteWidth(context) else 0f

            mainContent.setOnLongClickListener {
                if (openItemPosition != position) {
                    // Close previously open item if needed
                    val previousOpen = openItemPosition
                    openItemPosition = position
                    if (previousOpen != RecyclerView.NO_POSITION) {
                        notifyItemChanged(previousOpen, "close")
                    }
                    
                    // Animate this item open
                    mainContent.animate()
                        .translationX(-getDeleteWidth(context))
                        .setDuration(250)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1f))
                        .start()
                }
                true // Consume event
            }

            mainContent.setOnClickListener {
                if (openItemPosition == position) {
                    closeOpenItem()
                } else {
                    if (openItemPosition != RecyclerView.NO_POSITION) {
                        closeOpenItem()
                    } else {
                        onItemClickListener?.onItemClick(entry, item.originalPosition)
                    }
                }
            }

            deleteButtonContainer.setOnClickListener {
                closeOpenItem()
                onItemDeleteListener?.onItemDelete(entry, item.originalPosition)
            }

            // ── Amount display ──
            val formattedAmount = if (entry.getEntryUnit() != "sat") {
                val entryCurrency = Amount.Currency.fromCode(entry.getEntryUnit())
                val entryAmount = Amount(kotlin.math.abs(entry.enteredAmount), entryCurrency)
                entryAmount.toString()
            } else {
                val baseAmountSats = kotlin.math.abs(entry.getBaseAmountSats())
                val satAmount = Amount(baseAmountSats, Amount.Currency.BTC)
                satAmount.toString()
            }

            val displayAmount = if (isPending) {
                formattedAmount
            } else if (entry.amount >= 0) {
                "+$formattedAmount"
            } else {
                "-$formattedAmount"
            }
            amountText.text = displayAmount

            // ── Date ──
            dateText.text = dateFormat.format(entry.date)

            // ── Title: direction-based labels ──
            titleText.text = when {
                isPending -> context.getString(R.string.history_row_title_pending_payment)
                entry.amount >= 0 -> context.getString(R.string.history_row_title_payment_received)
                else -> context.getString(R.string.history_row_title_withdrawal)
            }

            // ── Direction icon ──
            val isIncoming = entry.amount >= 0
            icon.setImageResource(
                if (isIncoming) R.drawable.ic_arrow_down_receive
                else R.drawable.ic_arrow_up_send
            )
            icon.setColorFilter(context.getColor(R.color.color_text_primary))

            // ── Status badge ──
            if (isPending) {
                statusBadge.setBackgroundResource(R.drawable.bg_status_badge_orange)
                statusBadgeIcon.setImageResource(R.drawable.ic_clock_small)
            } else {
                statusBadge.setBackgroundResource(R.drawable.bg_status_badge_green)
                statusBadgeIcon.setImageResource(R.drawable.ic_check_small)
            }
            statusBadge.visibility = View.VISIBLE

            // ── Status text (pending only) ──
            if (isPending) {
                statusText.visibility = View.VISIBLE
                statusText.text = context.getString(R.string.history_row_status_tap_to_resume)
                statusText.setTextColor(context.getColor(R.color.color_warning))
            } else {
                statusText.visibility = View.GONE
            }

            // ── Label subtitle ──
            if (!entry.label.isNullOrBlank()) {
                subtitleText.text = entry.label
                subtitleText.visibility = View.VISIBLE
            } else {
                subtitleText.visibility = View.GONE
            }

            // ── Click handler ──
            itemView.setOnClickListener {
                onItemClickListener?.onItemClick(entry, item.originalPosition)
            }
        }
    }
}
