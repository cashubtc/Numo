package com.electricdreams.shellshock.feature.items;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.widget.EditText;
import com.electricdreams.shellshock.R;
import com.electricdreams.shellshock.core.model.Item;
import com.electricdreams.shellshock.core.util.ItemManager;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class ItemEntryActivity extends AppCompatActivity {

    public static final String EXTRA_ITEM_ID = "extra_item_id";
    private static final int REQUEST_IMAGE_CAPTURE = 1001;
    private static final int REQUEST_PICK_IMAGE = 1002;

    private EditText nameInput;
    private EditText variationInput;
    private EditText priceInput;
    private EditText skuInput;
    private EditText descriptionInput;
    private EditText categoryInput;
    private EditText quantityInput;
    private CheckBox alertCheckbox;
    private EditText alertThresholdInput;
    private ImageView itemImageView;
    private ImageView imagePlaceholder;
    private Button addImageButton;
    private Button removeImageButton;

    private ItemManager itemManager;
    private String editItemId;
    private boolean isEditMode;
    private Uri selectedImageUri;
    private Item currentItem;
    private String currentPhotoPath;

    private final ActivityResultLauncher<String> selectGalleryLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    selectedImageUri = uri;
                    updateImagePreview();
                }
            });
    
    private final ActivityResultLauncher<Uri> takePictureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && selectedImageUri != null) {
                    updateImagePreview();
                }
            });

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
        itemImageView = findViewById(R.id.item_image_view);
        imagePlaceholder = findViewById(R.id.item_image_placeholder);
        addImageButton = findViewById(R.id.item_add_image_button);
        removeImageButton = findViewById(R.id.item_remove_image_button);

        Button cancelButton = findViewById(R.id.item_cancel_button);
        Button saveButton = findViewById(R.id.item_save_button);
        View backButton = findViewById(R.id.back_button);

        // Set up image buttons
        addImageButton.setOnClickListener(v -> showImageSourceDialog());
        removeImageButton.setOnClickListener(v -> removeImage());

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
            // Update title and button for edit mode
            TextView toolbarTitle = findViewById(R.id.toolbar_title);
            if (toolbarTitle != null) {
                toolbarTitle.setText("Edit Item");
            }
            
            // Change cancel button to delete button
            cancelButton.setText("Delete Item");
            cancelButton.setTextColor(getResources().getColor(R.color.color_warning_red, null));
            
            loadItemData();
        } else {
            // setTitle("Add Item"); // Toolbar handles title
        }

        // Set up button listeners
        if (isEditMode) {
            // In edit mode, cancel button becomes delete button
            cancelButton.setOnClickListener(v -> showDeleteConfirmationDialog());
        } else {
            // In add mode, cancel button just closes
            cancelButton.setOnClickListener(v -> finish());
        }
        
        saveButton.setOnClickListener(v -> saveItem());
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }
    }
    
    private void showDeleteConfirmationDialog() {
        // Create Cash App style delete confirmation dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        // Inflate custom view for the dialog
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_delete_confirmation, null);
        builder.setView(dialogView);
        
        // Create the dialog
        AlertDialog dialog = builder.create();
        
        // Set up button listeners
        Button cancelButton = dialogView.findViewById(R.id.dialog_cancel_button);
        Button confirmButton = dialogView.findViewById(R.id.dialog_confirm_button);
        
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        
        confirmButton.setOnClickListener(v -> {
            // Delete the item
            if (currentItem != null) {
                itemManager.removeItem(currentItem.getId());
                Toast.makeText(this, "Item deleted", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                dialog.dismiss();
                finish();
            }
        });
        
        dialog.show();
    }

    private void loadItemData() {
        for (Item item : itemManager.getAllItems()) {
            if (item.getId().equals(editItemId)) {
                currentItem = item;
                
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
                
                // Load item image if available
                if (item.getImagePath() != null) {
                    Bitmap bitmap = itemManager.loadItemImage(item);
                    if (bitmap != null) {
                        itemImageView.setImageBitmap(bitmap);
                        itemImageView.setVisibility(View.VISIBLE);
                        imagePlaceholder.setVisibility(View.GONE);
                        removeImageButton.setVisibility(View.VISIBLE);
                    }
                }
                
                break;
            }
        }
    }
    
    private void showImageSourceDialog() {
        CharSequence[] options = {"Take Photo", "Choose from Gallery"};
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Picture");
        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: // Take Photo
                    takePicture();
                    break;
                case 1: // Choose from Gallery
                    selectFromGallery();
                    break;
            }
        });
        builder.show();
    }
    
    private void selectFromGallery() {
        selectGalleryLauncher.launch("image/*");
    }
    
    private void takePicture() {
        // First check if camera permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_IMAGE_CAPTURE);
            return;
        }
        
        try {
            // Create a file to store the image
            File photoFile = createImageFile();
            
            // Create the URI where the photo will be saved
            selectedImageUri = FileProvider.getUriForFile(
                    this,
                    "com.electricdreams.shellshock.fileprovider",
                    photoFile
            );
            
            // Start camera using the ActivityResultLauncher
            takePictureLauncher.launch(selectedImageUri);
            
        } catch (IOException ex) {
            // Error occurred while creating the file
            Toast.makeText(this, "Error creating image file: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }
    
    private void removeImage() {
        selectedImageUri = null;
        itemImageView.setImageBitmap(null);
        itemImageView.setVisibility(View.VISIBLE);
        imagePlaceholder.setVisibility(View.VISIBLE);
        removeImageButton.setVisibility(View.GONE);
        
        // If editing an item that already has an image, mark it for removal
        if (isEditMode && currentItem != null && currentItem.getImagePath() != null) {
            itemManager.deleteItemImage(currentItem);
        }
    }
    
    private void updateImagePreview() {
        if (selectedImageUri != null) {
            try {
                Bitmap bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                        getContentResolver(), selectedImageUri);
                itemImageView.setImageBitmap(bitmap);
                itemImageView.setVisibility(View.VISIBLE);
                imagePlaceholder.setVisibility(View.GONE);
                removeImageButton.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_IMAGE_CAPTURE) {
            // If request is cancelled, the result arrays are empty
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted, call camera again
                takePicture();
            } else {
                // Permission denied
                Toast.makeText(this, "Camera permission is required to take pictures", Toast.LENGTH_SHORT).show();
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
            
            // Preserve existing image path if not changed
            if (currentItem != null && currentItem.getImagePath() != null && selectedImageUri == null) {
                item.setImagePath(currentItem.getImagePath());
            }
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

        // First save the item data
        boolean success;
        if (isEditMode) {
            success = itemManager.updateItem(item);
        } else {
            success = itemManager.addItem(item);
        }

        if (!success) {
            Toast.makeText(this, "Failed to save item", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Then save the image if one was selected
        if (selectedImageUri != null) {
            boolean imageSaved = itemManager.saveItemImage(item, selectedImageUri);
            if (!imageSaved) {
                Toast.makeText(this, "Item saved but image could not be saved", Toast.LENGTH_LONG).show();
            }
        }

        Toast.makeText(this, isEditMode ? "Item updated" : "Item added", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
