package com.electricdreams.shellshock.feature.settings;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.util.MintManager;
import com.electricdreams.shellshock.ui.adapter.MintsAdapter;

public class MintsSettingsActivity extends AppCompatActivity implements MintsAdapter.MintRemoveListener {
    
    private RecyclerView mintsRecyclerView;
    private MintsAdapter mintsAdapter;
    private EditText newMintEditText;
    private Button addMintButton;
    private View resetMintsButton;
    private MintManager mintManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mints_settings);
        
        // Setup back button
        View backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }
        
        // Initialize manager
        mintManager = MintManager.getInstance(this);
        
        // Initialize views
        mintsRecyclerView = findViewById(R.id.mints_recycler_view);
        newMintEditText = findViewById(R.id.new_mint_edit_text);
        addMintButton = findViewById(R.id.add_mint_button);
        resetMintsButton = findViewById(R.id.reset_mints_button);
        
        // Set up RecyclerView
        mintsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        // Set up adapter
        mintsAdapter = new MintsAdapter(mintManager.getAllowedMints(), this);
        mintsRecyclerView.setAdapter(mintsAdapter);
        
        // Set up add mint button - auto-saves
        addMintButton.setOnClickListener(v -> addNewMint());
        
        // Set up reset mints button - auto-saves
        resetMintsButton.setOnClickListener(v -> resetMintsToDefaults());
        
        // Set up EditText done action
        newMintEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNewMint();
                return true;
            }
            return false;
        });
    }
    
    private void addNewMint() {
        String mintUrl = newMintEditText.getText().toString().trim();
        if (TextUtils.isEmpty(mintUrl)) {
            Toast.makeText(this, "Please enter a valid mint URL", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Add the mint - auto-saved by MintManager
        boolean added = mintManager.addMint(mintUrl);
        if (added) {
            mintsAdapter.updateMints(mintManager.getAllowedMints());
            newMintEditText.setText("");
            Toast.makeText(this, "Mint added", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Mint already in the list", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void resetMintsToDefaults() {
        // Reset - auto-saved by MintManager
        mintManager.resetToDefaults();
        mintsAdapter.updateMints(mintManager.getAllowedMints());
        Toast.makeText(this, "Mints reset to defaults", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onMintRemoved(String mintUrl) {
        // Remove - auto-saved by MintManager
        if (mintManager.removeMint(mintUrl)) {
            mintsAdapter.updateMints(mintManager.getAllowedMints());
            Toast.makeText(this, "Mint removed", Toast.LENGTH_SHORT).show();
        }
    }
}
