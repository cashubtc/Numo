package com.electricdreams.shellshock.feature.history;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.data.model.PaymentHistoryEntry;
import com.electricdreams.shellshock.ui.adapter.PaymentsHistoryAdapter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PaymentsHistoryActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "PaymentHistory";
    private static final String KEY_HISTORY = "history";
    private static final int REQUEST_TRANSACTION_DETAIL = 1001;

    private PaymentsHistoryAdapter adapter;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        // Setup Back Button
        View backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_TRANSACTION_DETAIL && resultCode == RESULT_OK && data != null) {
            int positionToDelete = data.getIntExtra("position_to_delete", -1);
            if (positionToDelete >= 0) {
                deletePaymentFromHistory(positionToDelete);
            }
        }
    }

    private void showTransactionDetails(PaymentHistoryEntry entry, int position) {
        Intent intent = new Intent(this, TransactionDetailActivity.class);
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_TOKEN, entry.getToken());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_AMOUNT, entry.getAmount());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_DATE, entry.getDate().getTime());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_UNIT, entry.getUnit());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTRY_UNIT, entry.getEntryUnit());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_ENTERED_AMOUNT, entry.getEnteredAmount());
        if (entry.getBitcoinPrice() != null) {
            intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_BITCOIN_PRICE, entry.getBitcoinPrice());
        }
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_MINT_URL, entry.getMintUrl());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_PAYMENT_REQUEST, entry.getPaymentRequest());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_IS_LIGHTNING, entry.isLightningPayment());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_LIGHTNING_QUOTE, entry.getLightningQuoteJson());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_LIGHTNING_BOLT11, entry.getLightningBolt11());
        intent.putExtra(TransactionDetailActivity.EXTRA_TRANSACTION_POSITION, position);
        
        startActivityForResult(intent, REQUEST_TRANSACTION_DETAIL);
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

    /**
     * Add a payment to history with comprehensive information
     * @param context Application context
     * @param token The cashu token
     * @param amount The amount in smallest unit (sats)
     * @param unit The unit of the cashu token (e.g., "sat")
     * @param entryUnit The unit with which it was entered (e.g., "USD", "sat")
     * @param enteredAmount The amount as it was entered (cents for fiat, sats for BTC)
     * @param bitcoinPrice The Bitcoin price at time of payment (can be null)
     * @param mintUrl The mint URL from which the token was received
     * @param paymentRequest The payment request used (can be null)
     */
    public static void addToHistory(Context context, String token, long amount,
                                    String unit, String entryUnit, long enteredAmount,
                                    Double bitcoinPrice, String mintUrl, String paymentRequest,
                                    boolean lightningPayment, String lightningQuoteJson,
                                    String lightningBolt11) {
        List<PaymentHistoryEntry> history = getPaymentHistory(context);
        history.add(new PaymentHistoryEntry(token, amount, new java.util.Date(), 
                                           unit, entryUnit, enteredAmount, bitcoinPrice, 
                                           mintUrl, paymentRequest, lightningPayment,
                                           lightningQuoteJson, lightningBolt11));
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_HISTORY, new Gson().toJson(history));
        editor.apply();
    }

    /**
     * Legacy method for backward compatibility
     * @deprecated Use {@link #addToHistory(Context, String, long, String, String, long, Double, String, String)} instead
     */
    @Deprecated
    public static void addToHistory(Context context, String token, long amount) {
        addToHistory(context, token, amount, "sat", "sat", amount,
                null, null, null, false, null, null);
    }

    public static void addToHistory(Context context, String token, long amount,
                                    String unit, String entryUnit, long enteredAmount,
                                    Double bitcoinPrice, String mintUrl, String paymentRequest) {
        addToHistory(context, token, amount, unit, entryUnit, enteredAmount,
                bitcoinPrice, mintUrl, paymentRequest, false, null, null);
    }
}
