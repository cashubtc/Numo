package com.electricdreams.shellshock;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.electricdreams.shellshock.util.CurrencyManager;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    
    private RadioGroup currencyRadioGroup;
    private RadioButton radioUsd;
    private RadioButton radioEur;
    private RadioButton radioGbp;
    private RadioButton radioJpy;
    private Button saveButton;
    
    private CurrencyManager currencyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        // Initialize CurrencyManager
        currencyManager = CurrencyManager.getInstance(this);
        
        // Initialize views
        currencyRadioGroup = findViewById(R.id.currency_radio_group);
        radioUsd = findViewById(R.id.radio_usd);
        radioEur = findViewById(R.id.radio_eur);
        radioGbp = findViewById(R.id.radio_gbp);
        radioJpy = findViewById(R.id.radio_jpy);
        saveButton = findViewById(R.id.save_button);
        
        // Set current selection based on saved preference
        setSelectedCurrency(currencyManager.getCurrentCurrency());
        
        // Set up save button
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
     * Save the selected settings
     */
    private void saveSettings() {
        String selectedCurrency = getSelectedCurrency();
        Log.d(TAG, "Saving currency preference: " + selectedCurrency);
        
        // Save the setting
        boolean success = currencyManager.setPreferredCurrency(selectedCurrency);
        
        if (success) {
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Error saving settings", Toast.LENGTH_SHORT).show();
        }
    }
}
