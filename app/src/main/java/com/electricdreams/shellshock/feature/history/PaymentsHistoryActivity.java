package com.electricdreams.shellshock.feature.history;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry;
import com.electricdreams.shellshock.ui.adapter.PaymentsHistoryAdapter;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PaymentsHistoryActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "PaymentHistory";
    private static final String KEY_HISTORY = "history";

    private PaymentsHistoryAdapter adapter;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        // Setup Profile Icon for Menu
        View profileIcon = findViewById(R.id.profile_icon);
        profileIcon.setOnClickListener(this::showProfileMenu);

        // Setup RecyclerView
        RecyclerView recyclerView = findViewById(R.id.history_recycler_view);
        emptyView = findViewById(R.id.empty_view);
        
        adapter = new PaymentsHistoryAdapter();
        adapter.setOnItemClickListener(this::showTransactionDetails);
        
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Load and display history
        loadHistory();
    }

    private void showProfileMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add("Clear History");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Clear History")) {
                showClearHistoryConfirmation();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showTransactionDetails(PaymentHistoryEntry entry, int position) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_transaction_details);

        TextView amountText = sheet.findViewById(R.id.detail_amount);
        TextView dateText = sheet.findViewById(R.id.detail_date);
        TextView tokenText = sheet.findViewById(R.id.detail_token);
        Button copyButton = sheet.findViewById(R.id.btn_copy);
        Button openWithButton = sheet.findViewById(R.id.btn_open_with);
        Button deleteButton = sheet.findViewById(R.id.btn_delete);

        if (amountText != null) {
            long amount = entry.getAmount();
            String sign = amount >= 0 ? "+" : "";
            amountText.setText(String.format(Locale.getDefault(), "%s $%d", sign, Math.abs(amount)));
        }
        
        if (dateText != null) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault());
            dateText.setText(dateFormat.format(entry.getDate()));
        }

        if (tokenText != null) {
            tokenText.setText(entry.getToken());
        }

        if (copyButton != null) {
            copyButton.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Payment", entry.getToken());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Payment copied to clipboard", Toast.LENGTH_SHORT).show();
                sheet.dismiss();
            });
        }

        if (openWithButton != null) {
            openWithButton.setOnClickListener(v -> {
                openPaymentWithApp(entry.getToken());
                sheet.dismiss();
            });
        }

        if (deleteButton != null) {
            deleteButton.setOnClickListener(v -> {
                sheet.dismiss();
                showDeleteConfirmation(entry, position);
            });
        }

        sheet.show();
    }

    private void openPaymentWithApp(String token) {
        String cashuUri = "cashu:" + token;
        Intent uriIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(cashuUri));
        uriIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, cashuUri);
        
        Intent chooserIntent = Intent.createChooser(uriIntent, "Open payment with...");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { shareIntent });
        
        try {
            startActivity(chooserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "No apps available to handle this payment", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeleteConfirmation(PaymentHistoryEntry entry, int position) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Payment")
            .setMessage("Are you sure you want to delete this payment from history?")
            .setPositiveButton("Delete", (dialog, which) -> deletePaymentFromHistory(position))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showClearHistoryConfirmation() {
        new AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to clear all payment history? This action cannot be undone.")
            .setPositiveButton("Clear All", (dialog, which) -> clearAllHistory())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadHistory() {
        List<PaymentHistoryEntry> history = getPaymentHistory();
        Collections.reverse(history); // Show newest first
        adapter.setEntries(history);
        
        boolean isEmpty = history.isEmpty();
        if (emptyView != null) {
            emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
    }

    private void clearAllHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_HISTORY, "[]");
        editor.apply();
        loadHistory();
    }

    private void deletePaymentFromHistory(int position) {
        List<PaymentHistoryEntry> history = getPaymentHistory();
        Collections.reverse(history);
        if (position >= 0 && position < history.size()) {
            history.remove(position);
            Collections.reverse(history);
            
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_HISTORY, new Gson().toJson(history));
            editor.apply();
            
            loadHistory();
        }
    }

    public static List<PaymentHistoryEntry> getPaymentHistory(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        Type type = new TypeToken<ArrayList<PaymentHistoryEntry>>(){}.getType();
        return new Gson().fromJson(json, type);
    }

    private List<PaymentHistoryEntry> getPaymentHistory() {
        return getPaymentHistory(this);
    }

    public static void addToHistory(Context context, String token, long amount) {
        List<PaymentHistoryEntry> history = getPaymentHistory(context);
        history.add(new PaymentHistoryEntry(token, amount, new java.util.Date()));
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_HISTORY, new Gson().toJson(history));
        editor.apply();
    }
}
