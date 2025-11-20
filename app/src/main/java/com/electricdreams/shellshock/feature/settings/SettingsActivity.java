package com.electricdreams.shellshock.feature.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.electricdreams.shellshock.R;

import com.electricdreams.shellshock.core.util.CurrencyManager;
import com.electricdreams.shellshock.core.util.ItemManager;
import com.electricdreams.shellshock.core.util.MintManager;
import com.electricdreams.shellshock.feature.items.ItemListActivity;
import com.electricdreams.shellshock.ui.adapter.MintsAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class SettingsActivity extends AppCompatActivity implements MintsAdapter.MintRemoveListener {
    private static final String TAG = "SettingsActivity";
    
    private RadioGroup currencyRadioGroup;
    private RadioButton radioUsd;
    private RadioButton radioEur;
    private RadioButton radioGbp;
    private RadioButton radioJpy;
    private Button saveButton;
    private RecyclerView mintsRecyclerView;
    private MintsAdapter mintsAdapter;
    private EditText newMintEditText;
    private Button addMintButton;
    private Button resetMintsButton;
    
    private CurrencyManager currencyManager;
    private MintManager mintManager;

    // Define launchers for file picking and item list activity
    private final ActivityResultLauncher<String> csvPickerLauncher = 
            registerForActivityResult(new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    importCsvFile(uri);
                }
            });
            
    private final ActivityResultLauncher<Intent> itemListLauncher = 
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // Refresh item count display
                    updateItemsStatus();
                }
            });
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        // Initialize managers
        currencyManager = CurrencyManager.getInstance(this);
        mintManager = MintManager.getInstance(this);
        
        // Initialize currency views
        currencyRadioGroup = findViewById(R.id.currency_radio_group);
        radioUsd = findViewById(R.id.radio_usd);
        radioEur = findViewById(R.id.radio_eur);
        radioGbp = findViewById(R.id.radio_gbp);
        radioJpy = findViewById(R.id.radio_jpy);
        
        // Initialize mints views
        mintsRecyclerView = findViewById(R.id.mints_recycler_view);
        newMintEditText = findViewById(R.id.new_mint_edit_text);
        addMintButton = findViewById(R.id.add_mint_button);
        resetMintsButton = findViewById(R.id.reset_mints_button);
        
        // Set up the RecyclerView
        mintsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mintsRecyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        
        // Set up the adapter
        mintsAdapter = new MintsAdapter(mintManager.getAllowedMints(), this);
        mintsRecyclerView.setAdapter(mintsAdapter);
        
        // Set up add mint button
        addMintButton.setOnClickListener(v -> addNewMint());
        
        // Set up reset mints button
        resetMintsButton.setOnClickListener(v -> resetMintsToDefaults());
        
        // Set up EditText done action
        newMintEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNewMint();
                return true;
            }
            return false;
        });
        
        // Set current currency selection based on saved preference
        setSelectedCurrency(currencyManager.getCurrentCurrency());
        
        // Initialize items section
        initializeItemsSection();
        
        // Initialize save button
        saveButton = findViewById(R.id.save_button);
        saveButton.setOnClickListener(v -> saveSettings());
    }
    
    /**
     * Set the radio button selection based on the current currency
     */
    private void setSelectedCurrency(String currencyCode) {
        switch (currencyCode) {
            case CurrencyManager.CURRENCY_EUR:
                radioEur.setChecked(true);
                break;
            case CurrencyManager.CURRENCY_GBP:
                radioGbp.setChecked(true);
                break;
            case CurrencyManager.CURRENCY_JPY:
                radioJpy.setChecked(true);
                break;
            case CurrencyManager.CURRENCY_USD:
            default:
                radioUsd.setChecked(true);
                break;
        }
    }
    
    /**
     * Get the selected currency from the radio group
     */
    private String getSelectedCurrency() {
        int selectedId = currencyRadioGroup.getCheckedRadioButtonId();
        
        if (selectedId == R.id.radio_eur) {
            return CurrencyManager.CURRENCY_EUR;
        } else if (selectedId == R.id.radio_gbp) {
            return CurrencyManager.CURRENCY_GBP;
        } else if (selectedId == R.id.radio_jpy) {
            return CurrencyManager.CURRENCY_JPY;
        } else {
            return CurrencyManager.CURRENCY_USD;
        }
    }
    
    /**
     * Add a new mint to the allowed list
     */
    private void addNewMint() {
        String mintUrl = newMintEditText.getText().toString().trim();
        if (TextUtils.isEmpty(mintUrl)) {
            Toast.makeText(this, "Please enter a valid mint URL", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Add the mint
        boolean added = mintManager.addMint(mintUrl);
        if (added) {
            // Update the adapter
            mintsAdapter.updateMints(mintManager.getAllowedMints());
            // Clear the input field
            newMintEditText.setText("");
            Toast.makeText(this, "Mint added", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Mint already in the list", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Reset mints to the default list
     */
    private void resetMintsToDefaults() {
        mintManager.resetToDefaults();
        mintsAdapter.updateMints(mintManager.getAllowedMints());
        Toast.makeText(this, "Mints reset to defaults", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * Remove a mint from the allowed list (callback from adapter)
     */
    @Override
    public void onMintRemoved(String mintUrl) {
        if (mintManager.removeMint(mintUrl)) {
            mintsAdapter.updateMints(mintManager.getAllowedMints());
            Toast.makeText(this, "Mint removed", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Save the selected settings
     */
    private void saveSettings() {
        // Save currency preference
        String selectedCurrency = getSelectedCurrency();
        Log.d(TAG, "Saving currency preference: " + selectedCurrency);
        boolean currencySuccess = currencyManager.setPreferredCurrency(selectedCurrency);
        
        // Get current list of allowed mints for logging
        List<String> allowedMints = mintManager.getAllowedMints();
        Log.d(TAG, "Current allowed mints (" + allowedMints.size() + "): " + TextUtils.join(", ", allowedMints));
        
        if (currencySuccess) {
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Error saving settings", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Initialize the items section of the settings screen
     */
    private void initializeItemsSection() {
        View itemsSection = findViewById(R.id.items_section);
        if (itemsSection == null) {
            Log.e(TAG, "Items section not found in layout");
            return;
        }
        
        TextView itemsStatusText = itemsSection.findViewById(R.id.items_status_text);
        Button importItemsButton = itemsSection.findViewById(R.id.import_items_button);
        Button addItemsButton = itemsSection.findViewById(R.id.add_items_button);
        Button clearItemsButton = itemsSection.findViewById(R.id.clear_items_button);
        
        // Update status text
        updateItemsStatus();
        
        // Set up import button
        importItemsButton.setOnClickListener(v -> {
            csvPickerLauncher.launch("text/csv");
        });
        
        // Set up add items button
        addItemsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ItemListActivity.class);
            itemListLauncher.launch(intent);
        });
        
        // Update button tooltip/description
        addItemsButton.setTooltipText("View, add, edit, or delete catalog items");
        
        // Set up clear button
        clearItemsButton.setOnClickListener(v -> {
            // Show confirmation dialog before clearing items
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Clear All Items")
                    .setMessage("Are you sure you want to delete ALL items from your catalog? This cannot be undone.")
                    .setPositiveButton("Delete All Items", (dialog, which) -> {
                        ItemManager itemManager = ItemManager.getInstance(this);
                        itemManager.clearItems();
                        updateItemsStatus();
                        Toast.makeText(this, "All items cleared", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }
    
    /**
     * Update the items status text to reflect current item count
     */
    private void updateItemsStatus() {
        View itemsSection = findViewById(R.id.items_section);
        if (itemsSection == null) {
            return;
        }
        
        TextView itemsStatusText = itemsSection.findViewById(R.id.items_status_text);
        ItemManager itemManager = ItemManager.getInstance(this);
        int itemCount = itemManager.getAllItems().size();
        
        if (itemCount == 0) {
            itemsStatusText.setText("No items in catalog");
        } else {
            itemsStatusText.setText(itemCount + " items in catalog");
        }
    }
    
    /**
     * Import a CSV file from a URI
     * @param uri URI of the CSV file
     */
    private void importCsvFile(Uri uri) {
        try {
            // Create a temporary file to store the CSV
            File tempFile = new File(getCacheDir(), "import_catalog.csv");
            
            // Copy the content from the URI to the temp file
            try (InputStream inputStream = getContentResolver().openInputStream(uri);
                 OutputStream outputStream = new FileOutputStream(tempFile)) {
                
                if (inputStream == null) {
                    Toast.makeText(this, "Failed to open CSV file", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            
            // Now import the items from the temp file
            ItemManager itemManager = ItemManager.getInstance(this);
            int importedCount = itemManager.importItemsFromCsv(tempFile.getAbsolutePath(), true);
            
            if (importedCount > 0) {
                Toast.makeText(this, "Imported " + importedCount + " items", Toast.LENGTH_SHORT).show();
                updateItemsStatus();
            } else {
                Toast.makeText(this, "No items imported from CSV", Toast.LENGTH_SHORT).show();
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error importing CSV file: " + e.getMessage(), e);
            Toast.makeText(this, "Error importing CSV file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
