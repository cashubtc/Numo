package com.electricdreams.shellshock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TokenHistoryActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "TokenHistory";
    private static final String KEY_HISTORY = "history";

    private TokenHistoryAdapter adapter;
    private TextView emptyView;
    private FloatingActionButton clearHistoryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        // Setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Token History");
        }

        // Setup RecyclerView
        RecyclerView recyclerView = findViewById(R.id.history_recycler_view);
        emptyView = findViewById(R.id.empty_view);
        
        adapter = new TokenHistoryAdapter();
        adapter.setOnDeleteClickListener((entry, position) -> {
            new AlertDialog.Builder(this)
                .setTitle("Delete Token")
                .setMessage("Are you sure you want to delete this token from history?")
                .setPositiveButton("Delete", (dialog, which) -> deleteTokenFromHistory(position))
                .setNegativeButton("Cancel", null)
                .show();
        });
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Set up clear history button
        clearHistoryButton = findViewById(R.id.clear_history_button);
        clearHistoryButton.setOnClickListener(v -> showClearHistoryConfirmation());

        // Load and display history
        loadHistory();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadHistory() {
        List<TokenHistoryEntry> history = getTokenHistory();
        Collections.reverse(history); // Show newest first
        adapter.setEntries(history);
        
        // Show/hide empty view
        boolean isEmpty = history.isEmpty();
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        
        // Show/hide clear button based on if there are entries
        clearHistoryButton.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void showClearHistoryConfirmation() {
        new AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to clear all token history? This action cannot be undone.")
            .setPositiveButton("Clear All", (dialog, which) -> clearAllHistory())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void clearAllHistory() {
        // Clear history in shared preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_HISTORY, "[]");
        editor.apply();
        
        // Reload the view
        loadHistory();
    }

    private void deleteTokenFromHistory(int position) {
        List<TokenHistoryEntry> history = getTokenHistory();
        Collections.reverse(history); // Since we show newest first
        if (position >= 0 && position < history.size()) {
            history.remove(position);
            Collections.reverse(history); // Reverse back for storage
            
            // Save updated history
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_HISTORY, new Gson().toJson(history));
            editor.apply();
            
            // Reload the view
            loadHistory();
        }
    }

    public static List<TokenHistoryEntry> getTokenHistory(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        Type type = new TypeToken<ArrayList<TokenHistoryEntry>>(){}.getType();
        return new Gson().fromJson(json, type);
    }

    private List<TokenHistoryEntry> getTokenHistory() {
        return getTokenHistory(this);
    }

    public static void addToHistory(Context context, String token, long amount) {
        List<TokenHistoryEntry> history = getTokenHistory(context);
        history.add(new TokenHistoryEntry(token, amount, new java.util.Date()));
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_HISTORY, new Gson().toJson(history));
        editor.apply();
    }
}
