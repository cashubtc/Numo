package com.electricdreams.shellshock.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PaymentsHistoryAdapter extends RecyclerView.Adapter<PaymentsHistoryAdapter.ViewHolder> {
    private final List<PaymentHistoryEntry> entries = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());
    private OnItemClickListener onItemClickListener;

    public interface OnItemClickListener {
        void onItemClick(PaymentHistoryEntry entry, int position);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    public void setEntries(List<PaymentHistoryEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_payment_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PaymentHistoryEntry entry = entries.get(position);
        
        // Display amount in the unit it was entered
        String formattedAmount;
        if (entry.getEntryUnit() != null && !entry.getEntryUnit().equals("sat")) {
            // Display in fiat currency (USD, EUR, etc.)
            com.electricdreams.shellshock.core.model.Amount.Currency entryCurrency = 
                com.electricdreams.shellshock.core.model.Amount.Currency.fromCode(entry.getEntryUnit());
            com.electricdreams.shellshock.core.model.Amount entryAmount = 
                new com.electricdreams.shellshock.core.model.Amount(entry.getEnteredAmount(), entryCurrency);
            formattedAmount = entryAmount.toString();
        } else {
            // Display in sats
            long amount = entry.getAmount();
            com.electricdreams.shellshock.core.model.Amount satAmount = 
                new com.electricdreams.shellshock.core.model.Amount(amount, 
                    com.electricdreams.shellshock.core.model.Amount.Currency.BTC);
            formattedAmount = satAmount.toString();
        }
        
        // Add + sign for positive amounts
        if (entry.getAmount() >= 0) {
            formattedAmount = "+" + formattedAmount;
        }
        
        holder.amountText.setText(formattedAmount);
        
        // Set date
        holder.dateText.setText(dateFormat.format(entry.getDate()));
        
        // Set title based on amount (simple logic for now)
        if (entry.isLightningPayment()) {
            holder.titleText.setText("Lightning payment");
            holder.iconView.setImageResource(R.drawable.ic_lightning);
        } else if (entry.getAmount() > 0) {
            holder.titleText.setText("Cash In");
            holder.iconView.setImageResource(R.drawable.ic_bitcoin);
        } else {
            holder.titleText.setText("Cash Out");
            holder.iconView.setImageResource(R.drawable.ic_bitcoin);
        }

        holder.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null) {
                onItemClickListener.onItemClick(entry, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView amountText;
        final TextView dateText;
        final TextView titleText;
        final ImageView iconView;

        ViewHolder(View view) {
            super(view);
            amountText = view.findViewById(R.id.amount_text);
            dateText = view.findViewById(R.id.date_text);
            titleText = view.findViewById(R.id.title_text);
            iconView = view.findViewById(R.id.icon);
        }
    }
}
