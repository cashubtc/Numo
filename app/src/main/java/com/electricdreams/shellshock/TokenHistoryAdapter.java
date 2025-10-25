package com.electricdreams.shellshock;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TokenHistoryAdapter extends RecyclerView.Adapter<TokenHistoryAdapter.ViewHolder> {
    private final List<TokenHistoryEntry> entries = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault());
    private OnDeleteClickListener onDeleteClickListener;

    public interface OnDeleteClickListener {
        void onDeleteClick(TokenHistoryEntry entry, int position);
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
    }

    public void setEntries(List<TokenHistoryEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_token_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TokenHistoryEntry entry = entries.get(position);
        holder.amountText.setText(String.format(Locale.getDefault(), "%d sats", entry.getAmount()));
        holder.dateText.setText(dateFormat.format(entry.getDate()));
        
        holder.copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) v.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Token", entry.getToken());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(v.getContext(), "Token copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (onDeleteClickListener != null) {
                onDeleteClickListener.onDeleteClick(entry, position);
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
        final ImageButton copyButton;
        final ImageButton deleteButton;

        ViewHolder(View view) {
            super(view);
            amountText = view.findViewById(R.id.amount_text);
            dateText = view.findViewById(R.id.date_text);
            copyButton = view.findViewById(R.id.copy_button);
            deleteButton = view.findViewById(R.id.delete_button);
        }
    }
}
