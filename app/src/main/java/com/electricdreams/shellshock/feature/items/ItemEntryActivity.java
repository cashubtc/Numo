package com.electricdreams.shellshock.feature.items;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.model.Item;
import com.electricdreams.shellshock.core.util.ItemManager;
import com.google.android.material.textfield.TextInputEditText;

import java.util.UUID;

public class ItemEntryActivity extends AppCompatActivity {

    public static final String EXTRA_ITEM_ID = "extra_item_id";

    private TextInputEditText nameInput;
    private TextInputEditText variationInput;
    private TextInputEditText priceInput;
    private TextInputEditText skuInput;
    private TextInputEditText descriptionInput;
    private TextInputEditText categoryInput;
    private TextInputEditText quantityInput;
    private CheckBox alertCheckbox;
    private TextInputEditText alertThresholdInput;

    private ItemManager itemManager;
    private String editItemId;
    private boolean isEditMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_item_entry);

        // Initialize views
        nameInput = findViewById(R.id.item_name_input);
        variationInput = findViewById(R.id.item_variation_input);
        priceInput = findViewById(R.id.item_price_input);
        skuInput = findViewById(R.id.item_sku_input);
        descriptionInput = findViewById(R.id.item_description_input);
        categoryInput = findViewById(R.id.item_category_input);
        quantityInput = findViewById(R.id.item_quantity_input);
        alertCheckbox = findViewById(R.id.item_alert_checkbox);
        alertThresholdInput = findViewById(R.id.item_alert_threshold_input);

        Button cancelButton = findViewById(R.id.item_cancel_button);
        Button saveButton = findViewById(R.id.item_save_button);

        // Get item manager instance
        itemManager = ItemManager.getInstance(this);

        // Check if we're editing an existing item
        editItemId = getIntent().getStringExtra(EXTRA_ITEM_ID);
        isEditMode = !TextUtils.isEmpty(editItemId);

        // Set up alert checkbox listener
        alertCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            alertThresholdInput.setEnabled(isChecked);
        });

        // Load item data if in edit mode
        if (isEditMode) {
            setTitle("Edit Item");
            loadItemData();
        } else {
            setTitle("Add Item");
        }

        // Set up button listeners
        cancelButton.setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> saveItem());
    }

    private void loadItemData() {
        for (Item item : itemManager.getAllItems()) {
            if (item.getId().equals(editItemId)) {
                // Populate the form with item data
                nameInput.setText(item.getName());
                variationInput.setText(item.getVariationName());
                priceInput.setText(String.valueOf(item.getPrice()));
                skuInput.setText(item.getSku());
                descriptionInput.setText(item.getDescription());
                categoryInput.setText(item.getCategory());
                quantityInput.setText(String.valueOf(item.getQuantity()));
                alertCheckbox.setChecked(item.isAlertEnabled());
                alertThresholdInput.setEnabled(item.isAlertEnabled());
                alertThresholdInput.setText(String.valueOf(item.getAlertThreshold()));
                break;
            }
        }
    }

    private void saveItem() {
        // Validate required fields
        String name = nameInput.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            nameInput.setError("Item name is required");
            nameInput.requestFocus();
            return;
        }

        String priceStr = priceInput.getText().toString().trim();
        if (TextUtils.isEmpty(priceStr)) {
            priceInput.setError("Price is required");
            priceInput.requestFocus();
            return;
        }

        double price;
        try {
            price = Double.parseDouble(priceStr);
            if (price < 0) {
                priceInput.setError("Price must be positive");
                priceInput.requestFocus();
                return;
            }
        } catch (NumberFormatException e) {
            priceInput.setError("Invalid price format");
            priceInput.requestFocus();
            return;
        }

        // Parse quantity
        String quantityStr = quantityInput.getText().toString().trim();
        int quantity = 0;
        if (!TextUtils.isEmpty(quantityStr)) {
            try {
                quantity = Integer.parseInt(quantityStr);
                if (quantity < 0) {
                    quantityInput.setError("Quantity must be positive");
                    quantityInput.requestFocus();
                    return;
                }
            } catch (NumberFormatException e) {
                quantityInput.setError("Invalid quantity format");
                quantityInput.requestFocus();
                return;
            }
        }

        // Parse alert threshold
        int alertThreshold = 5;
        if (alertCheckbox.isChecked()) {
            String thresholdStr = alertThresholdInput.getText().toString().trim();
            if (!TextUtils.isEmpty(thresholdStr)) {
                try {
                    alertThreshold = Integer.parseInt(thresholdStr);
                    if (alertThreshold < 0) {
                        alertThresholdInput.setError("Threshold must be positive");
                        alertThresholdInput.requestFocus();
                        return;
                    }
                } catch (NumberFormatException e) {
                    alertThresholdInput.setError("Invalid threshold format");
                    alertThresholdInput.requestFocus();
                    return;
                }
            }
        }

        // Create or update item
        Item item = new Item();
        if (isEditMode) {
            item.setId(editItemId);
        } else {
            item.setId(UUID.randomUUID().toString());
        }
        
        item.setName(name);
        item.setVariationName(variationInput.getText().toString().trim());
        item.setPrice(price);
        item.setSku(skuInput.getText().toString().trim());
        item.setDescription(descriptionInput.getText().toString().trim());
        item.setCategory(categoryInput.getText().toString().trim());
        item.setQuantity(quantity);
        item.setAlertEnabled(alertCheckbox.isChecked());
        item.setAlertThreshold(alertThreshold);

        boolean success;
        if (isEditMode) {
            success = itemManager.updateItem(item);
        } else {
            success = itemManager.addItem(item);
        }

        if (success) {
            Toast.makeText(this, isEditMode ? "Item updated" : "Item added", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, "Failed to save item", Toast.LENGTH_SHORT).show();
        }
    }
}
