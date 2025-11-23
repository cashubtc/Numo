package com.electricdreams.shellshock.feature.items

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.util.ItemManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ItemEntryActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var variationInput: EditText
    private lateinit var priceInput: EditText
    private lateinit var skuInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var categoryInput: EditText
    private lateinit var quantityInput: EditText
    private lateinit var alertCheckbox: CheckBox
    private lateinit var alertThresholdInput: EditText
    private lateinit var itemImageView: ImageView
    private lateinit var imagePlaceholder: ImageView
    private lateinit var addImageButton: Button
    private lateinit var removeImageButton: Button

    private lateinit var itemManager: ItemManager
    private var editItemId: String? = null
    private var isEditMode: Boolean = false
    private var selectedImageUri: Uri? = null
    private var currentItem: Item? = null
    private var currentPhotoPath: String? = null

    private val selectGalleryLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                selectedImageUri = uri
                updateImagePreview()
            }
        }

    private val takePictureLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && selectedImageUri != null) {
                updateImagePreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_entry)

        nameInput = findViewById(R.id.item_name_input)
        variationInput = findViewById(R.id.item_variation_input)
        priceInput = findViewById(R.id.item_price_input)
        skuInput = findViewById(R.id.item_sku_input)
        descriptionInput = findViewById(R.id.item_description_input)
        categoryInput = findViewById(R.id.item_category_input)
        quantityInput = findViewById(R.id.item_quantity_input)
        alertCheckbox = findViewById(R.id.item_alert_checkbox)
        alertThresholdInput = findViewById(R.id.item_alert_threshold_input)
        itemImageView = findViewById(R.id.item_image_view)
        imagePlaceholder = findViewById(R.id.item_image_placeholder)
        addImageButton = findViewById(R.id.item_add_image_button)
        removeImageButton = findViewById(R.id.item_remove_image_button)

        val cancelButton: Button = findViewById(R.id.item_cancel_button)
        val saveButton: Button = findViewById(R.id.item_save_button)
        val backButton: View? = findViewById(R.id.back_button)

        addImageButton.setOnClickListener { showImageSourceDialog() }
        removeImageButton.setOnClickListener { removeImage() }

        itemManager = ItemManager.getInstance(this)

        editItemId = intent.getStringExtra(EXTRA_ITEM_ID)
        isEditMode = !editItemId.isNullOrEmpty()

        alertCheckbox.setOnCheckedChangeListener { _, isChecked ->
            alertThresholdInput.isEnabled = isChecked
        }

        if (isEditMode) {
            val toolbarTitle: TextView? = findViewById(R.id.toolbar_title)
            toolbarTitle?.text = "Edit Item"

            cancelButton.text = "Delete Item"
            cancelButton.setTextColor(resources.getColor(R.color.color_warning_red, null))

            loadItemData()
        }

        if (isEditMode) {
            cancelButton.setOnClickListener { showDeleteConfirmationDialog() }
        } else {
            cancelButton.setOnClickListener { finish() }
        }

        saveButton.setOnClickListener { saveItem() }
        backButton?.setOnClickListener { finish() }
    }

    private fun showDeleteConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_confirmation, null)
        builder.setView(dialogView)

        val dialog = builder.create()

        val cancelButton: Button = dialogView.findViewById(R.id.dialog_cancel_button)
        val confirmButton: Button = dialogView.findViewById(R.id.dialog_confirm_button)

        cancelButton.setOnClickListener { dialog.dismiss() }

        confirmButton.setOnClickListener {
            currentItem?.let { item ->
                itemManager.removeItem(item.id!!)
                Toast.makeText(this, "Item deleted", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                dialog.dismiss()
                finish()
            }
        }

        dialog.show()
    }

    private fun loadItemData() {
        for (item in itemManager.getAllItems()) {
            if (item.id == editItemId) {
                currentItem = item

                nameInput.setText(item.name)
                variationInput.setText(item.variationName)
                priceInput.setText(item.price.toString())
                skuInput.setText(item.sku)
                descriptionInput.setText(item.description)
                categoryInput.setText(item.category)
                quantityInput.setText(item.quantity.toString())
                alertCheckbox.isChecked = item.isAlertEnabled()
                alertThresholdInput.isEnabled = item.isAlertEnabled()
                alertThresholdInput.setText(item.alertThreshold.toString())

                if (!item.imagePath.isNullOrEmpty()) {
                    val bitmap = itemManager.loadItemImage(item)
                    if (bitmap != null) {
                        itemImageView.setImageBitmap(bitmap)
                        itemImageView.visibility = View.VISIBLE
                        imagePlaceholder.visibility = View.GONE
                        removeImageButton.visibility = View.VISIBLE
                    }
                }

                break
            }
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Take Photo", "Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Add Picture")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> takePicture()
                    1 -> selectFromGallery()
                }
            }
            .show()
    }

    private fun selectFromGallery() {
        selectGalleryLauncher.launch("image/*")
    }

    private fun takePicture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_IMAGE_CAPTURE)
            return
        }

        try {
            val photoFile = createImageFile()
            selectedImageUri = FileProvider.getUriForFile(
                this,
                "com.electricdreams.shellshock.fileprovider",
                photoFile,
            )
            takePictureLauncher.launch(selectedImageUri)
        } catch (ex: IOException) {
            Toast.makeText(this, "Error creating image file: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val image = File.createTempFile(imageFileName, ".jpg", storageDir)
        currentPhotoPath = image.absolutePath
        return image
    }

    private fun removeImage() {
        selectedImageUri = null
        itemImageView.setImageBitmap(null)
        itemImageView.visibility = View.VISIBLE
        imagePlaceholder.visibility = View.VISIBLE
        removeImageButton.visibility = View.GONE

        if (isEditMode && currentItem?.imagePath != null) {
            currentItem?.let { itemManager.deleteItemImage(it) }
        }
    }

    private fun updateImagePreview() {
        selectedImageUri?.let { uri ->
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                itemImageView.setImageBitmap(bitmap)
                itemImageView.visibility = View.VISIBLE
                imagePlaceholder.visibility = View.GONE
                removeImageButton.visibility = View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_IMAGE_CAPTURE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePicture()
            } else {
                Toast.makeText(this, "Camera permission is required to take pictures", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveItem() {
        val name = nameInput.text.toString().trim()
        if (TextUtils.isEmpty(name)) {
            nameInput.error = "Item name is required"
            nameInput.requestFocus()
            return
        }

        val priceStr = priceInput.text.toString().trim()
        if (TextUtils.isEmpty(priceStr)) {
            priceInput.error = "Price is required"
            priceInput.requestFocus()
            return
        }

        val price = priceStr.toDoubleOrNull()
        if (price == null || price < 0) {
            priceInput.error = "Price must be a positive number"
            priceInput.requestFocus()
            return
        }

        val quantityStr = quantityInput.text.toString().trim()
        var quantity = 0
        if (!TextUtils.isEmpty(quantityStr)) {
            val q = quantityStr.toIntOrNull()
            if (q == null || q < 0) {
                quantityInput.error = "Quantity must be positive"
                quantityInput.requestFocus()
                return
            }
            quantity = q
        }

        var alertThreshold = 5
        if (alertCheckbox.isChecked) {
            val thresholdStr = alertThresholdInput.text.toString().trim()
            if (!TextUtils.isEmpty(thresholdStr)) {
                val t = thresholdStr.toIntOrNull()
                if (t == null || t < 0) {
                    alertThresholdInput.error = "Threshold must be positive"
                    alertThresholdInput.requestFocus()
                    return
                }
                alertThreshold = t
            }
        }

        val item = Item().apply {
            if (isEditMode) {
                id = editItemId
                if (currentItem?.imagePath != null && selectedImageUri == null) {
                    imagePath = currentItem?.imagePath
                }
            } else {
                id = UUID.randomUUID().toString()
            }

            this.name = name
            variationName = variationInput.text.toString().trim()
            this.price = price
            sku = skuInput.text.toString().trim()
            description = descriptionInput.text.toString().trim()
            category = categoryInput.text.toString().trim()
            this.quantity = quantity
            alertEnabled = alertCheckbox.isChecked
            this.alertThreshold = alertThreshold
        }

        val success = if (isEditMode) {
            itemManager.updateItem(item)
        } else {
            itemManager.addItem(item)
        }

        if (!success) {
            Toast.makeText(this, "Failed to save item", Toast.LENGTH_SHORT).show()
            return
        }

        selectedImageUri?.let { uri ->
            val imageSaved = itemManager.saveItemImage(item, uri)
            if (!imageSaved) {
                Toast.makeText(this, "Item saved but image could not be saved", Toast.LENGTH_LONG).show()
            }
        }

        Toast.makeText(this, if (isEditMode) "Item updated" else "Item added", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        private const val REQUEST_IMAGE_CAPTURE = 1001
        private const val REQUEST_PICK_IMAGE = 1002
    }
}
