package com.electricdreams.shellshock.ui.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

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
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault());
    private OnDeleteClickListener onDeleteClickListener;

    public interface OnDeleteClickListener {
        void onDeleteClick(PaymentHistoryEntry entry, int position);
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
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
        holder.amountText.setText(String.format(Locale.getDefault(), "%d ₿", entry.getAmount()));
        holder.dateText.setText(dateFormat.format(entry.getDate()));
        
        holder.copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) v.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Payment", entry.getToken());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(v.getContext(), "Payment copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        holder.openWithButton.setOnClickListener(v -> openPaymentWithApp(v.getContext(), entry.getToken()));

        holder.deleteButton.setOnClickListener(v -> {
            if (onDeleteClickListener != null) {
                onDeleteClickListener.onDeleteClick(entry, position);
            }
        });
    }

    private void openPaymentWithApp(Context context, String token) {
        String cashuUri = "cashu:" + token;
        
        // Create intent for viewing the URI
        Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri));
        uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Create a fallback intent for sharing as text
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, cashuUri);
        
        // Combine both intents into a chooser
        Intent chooserIntent = Intent.createChooser(uriIntent, "Open payment with...");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { shareIntent });
        
        try {
            context.startActivity(chooserIntent);
        } catch (Exception e) {
            Toast.makeText(context, "No apps available to handle this payment", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView amountText;
        final TextView dateText;
        final ImageButton copyButton;
        final ImageButton openWithButton;
        final ImageButton deleteButton;

        ViewHolder(View view) {
            super(view);
            amountText = view.findViewById(R.id.amount_text);
            dateText = view.findViewById(R.id.date_text);
            copyButton = view.findViewById(R.id.copy_button);
            openWithButton = view.findViewById(R.id.open_with_button);
            deleteButton = view.findViewById(R.id.delete_button);
        }
    }
}
