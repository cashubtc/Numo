package com.electricdreams.shellshock.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        
        // Format amount
        long amount = entry.getAmount();
        String sign = amount >= 0 ? "+" : "";
        holder.amountText.setText(String.format(Locale.getDefault(), "%s $%d", sign, Math.abs(amount)));
        
        // Set date
        holder.dateText.setText(dateFormat.format(entry.getDate()));
        
        // Set title based on amount (simple logic for now)
        if (amount > 0) {
            holder.titleText.setText("Cash In");
        } else {
            holder.titleText.setText("Cash Out");
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

        ViewHolder(View view) {
            super(view);
            amountText = view.findViewById(R.id.amount_text);
            dateText = view.findViewById(R.id.date_text);
            titleText = view.findViewById(R.id.title_text);
        }
    }
}
