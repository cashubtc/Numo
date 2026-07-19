package com.electricdreams.numo.feature.autowithdraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for displaying withdraw history rows with expandable error details
 * for failed withdrawals and share/copy handling for Cashu token entries.
 */
class AutoWithdrawHistoryAdapter(
    private val entries: List<WithdrawHistoryEntry>
) : RecyclerView.Adapter<AutoWithdrawHistoryAdapter.ViewHolder>() {

    // Track expanded state for each item
    private val expandedItems = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconContainer: FrameLayout = view.findViewById(R.id.icon_container)
        val statusIcon: ImageView = view.findViewById(R.id.status_icon)
        val statusBadgeIcon: FrameLayout = view.findViewById(R.id.status_badge_icon_container)
        val amountText: TextView = view.findViewById(R.id.amount_text)
        val addressText: TextView = view.findViewById(R.id.address_text)
        val mintText: TextView = view.findViewById(R.id.mint_text)
        val timestampText: TextView = view.findViewById(R.id.timestamp_text)
        val statusBadge: TextView = view.findViewById(R.id.status_badge)
        val autoBadge: TextView = view.findViewById(R.id.auto_badge)
        val expandIndicator: ImageView = view.findViewById(R.id.expand_indicator)
        val errorContainer: LinearLayout = view.findViewById(R.id.error_container)
        val errorText: TextView = view.findViewById(R.id.error_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_auto_withdraw_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val context = holder.itemView.context

        // Format amount using Amount class with ₿ symbol
        val amount = Amount(entry.amountSats, Amount.Currency.BTC)
        holder.amountText.text = amount.toString()

        // Destination (address or invoice abbreviation)
        holder.addressText.text = entry.destination.ifBlank { entry.lightningAddress ?: "" }

        // Mint label
        holder.mintText.text = entry.mintUrl

        // Auto/manual badge
        if (entry.automatic) {
            holder.autoBadge.visibility = View.VISIBLE
        } else {
            holder.autoBadge.visibility = View.GONE
        }

        // Relative timestamp
        val dateFormat = SimpleDateFormat("MMM d • HH:mm", Locale.getDefault())
        holder.timestampText.text = dateFormat.format(Date(entry.timestamp))

        // Status styling
        when (entry.status) {
            WithdrawHistoryEntry.STATUS_COMPLETED -> {
                holder.statusIcon.setImageResource(R.drawable.ic_arrow_up_send)
                holder.statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.color_text_primary))
                holder.statusBadgeIcon.visibility = View.VISIBLE
                holder.statusBadge.visibility = View.GONE
                holder.expandIndicator.visibility = View.GONE
                holder.errorContainer.visibility = View.GONE
            }
            WithdrawHistoryEntry.STATUS_PENDING -> {
                holder.statusIcon.setImageResource(R.drawable.ic_pending)
                holder.statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.color_warning))
                holder.statusBadgeIcon.visibility = View.GONE
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.text = context.getString(R.string.auto_withdraw_status_pending)
                holder.statusBadge.setTextColor(ContextCompat.getColor(context, R.color.color_warning))
                holder.statusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_status_pill_pending)
                holder.expandIndicator.visibility = View.GONE
                holder.errorContainer.visibility = View.GONE
            }
            WithdrawHistoryEntry.STATUS_FAILED -> {
                holder.statusIcon.setImageResource(R.drawable.ic_close)
                holder.statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.color_error))
                holder.statusBadgeIcon.visibility = View.GONE
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.text = context.getString(R.string.auto_withdraw_status_failed)
                holder.statusBadge.setTextColor(ContextCompat.getColor(context, R.color.color_error))
                holder.statusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_status_pill_error)

                // Show expand indicator if there's an error message
                val hasError = !entry.errorMessage.isNullOrBlank()
                holder.expandIndicator.visibility = if (hasError) View.VISIBLE else View.GONE

                // Set error message
                holder.errorText.text = entry.errorMessage ?: ""

                // Check if this item is expanded
                val isExpanded = expandedItems.contains(entry.id)
                updateExpandState(holder, isExpanded, animate = false)

                // Set click listener to toggle expansion
                if (hasError) {
                    holder.itemView.setOnClickListener {
                        toggleExpand(entry.id, holder)
                    }
                } else {
                    holder.itemView.setOnClickListener(null)
                }
            }
        }

        if (entry.token != null) {
            holder.expandIndicator.visibility = View.VISIBLE
            holder.expandIndicator.setImageResource(R.drawable.ic_share)
            holder.expandIndicator.rotation = 0f
            holder.itemView.setOnClickListener {
                val cashuUri = "cashu:${entry.token}"
                val uriIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cashuUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, cashuUri)
                }
                val chooserIntent = Intent.createChooser(uriIntent, context.getString(R.string.token_history_open_with)).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
                }

                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Cashu Token", entry.token)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, R.string.withdraw_cashu_copied, Toast.LENGTH_SHORT).show()

                try {
                    context.startActivity(chooserIntent)
                } catch (e: Exception) {
                    // ignore if no app can handle it
                }
            }
        } else if (entry.status != WithdrawHistoryEntry.STATUS_FAILED) {
            holder.expandIndicator.setImageResource(R.drawable.ic_chevron_down)
            holder.itemView.setOnClickListener(null)
        } else {
            holder.expandIndicator.setImageResource(R.drawable.ic_chevron_down)
        }
    }

    private fun toggleExpand(entryId: String, holder: ViewHolder) {
        val isCurrentlyExpanded = expandedItems.contains(entryId)
        if (isCurrentlyExpanded) {
            expandedItems.remove(entryId)
        } else {
            expandedItems.add(entryId)
        }
        updateExpandState(holder, !isCurrentlyExpanded, animate = true)
    }

    private fun updateExpandState(holder: ViewHolder, isExpanded: Boolean, animate: Boolean) {
        if (animate) {
            // Rotate expand indicator
            val targetRotation = if (isExpanded) 180f else 0f
            holder.expandIndicator.animate()
                .rotation(targetRotation)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            // Animate error container
            if (isExpanded) {
                holder.errorContainer.visibility = View.VISIBLE
                holder.errorContainer.alpha = 0f
                holder.errorContainer.translationY = -10f
                holder.errorContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            } else {
                holder.errorContainer.animate()
                    .alpha(0f)
                    .translationY(-10f)
                    .setDuration(150)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        holder.errorContainer.visibility = View.GONE
                    }
                    .start()
            }
        } else {
            // Instant update without animation
            holder.expandIndicator.rotation = if (isExpanded) 180f else 0f
            holder.errorContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.errorContainer.alpha = if (isExpanded) 1f else 0f
        }
    }

    override fun getItemCount() = entries.size
}
