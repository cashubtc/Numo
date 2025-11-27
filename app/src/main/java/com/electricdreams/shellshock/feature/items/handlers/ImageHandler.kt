package com.electricdreams.shellshock.feature.items.handlers

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.electricdreams.shellshock.R

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.util.ItemManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles image capture, selection, and preview functionality including:
 * - Camera capture
 * - Gallery selection
 * - Image preview
 * - Image removal
 */
class ImageHandler(
    private val activity: AppCompatActivity,
    private val itemImageView: ImageView,
    private val imagePlaceholder: ImageView,
    private val addImageButton: Button,
    private val removeImageButton: Button,
    private val itemManager: ItemManager,
    private val selectGalleryLauncher: ActivityResultLauncher<String>,
    private val takePictureLauncher: ActivityResultLauncher<Uri>
) {
    companion object {
        const val REQUEST_IMAGE_CAPTURE = 1001
    }

    var selectedImageUri: Uri? = null
        private set

    private var currentPhotoPath: String? = null
    private var currentItem: Item? = null

    /**
     * Initializes the image handler with click listeners.
     */
    fun initialize() {
        addImageButton.setOnClickListener { showImageSourceDialog() }
        // Remove photo button is no longer needed - it's in the dialog now
        removeImageButton.visibility = View.GONE
    }

    /**
     * Sets the current item for edit mode.
     */
    fun setCurrentItem(item: Item?) {
        currentItem = item
    }

    /**
     * Returns whether an image is currently selected/displayed.
     */
    fun hasImage(): Boolean {
        return itemImageView.visibility == View.VISIBLE && imagePlaceholder.visibility == View.GONE
    }

    /**
     * Loads and displays an item's image (used when loading existing item data).
     */
    fun loadItemImage(item: Item) {
        if (!item.imagePath.isNullOrEmpty()) {
            val bitmap = itemManager.loadItemImage(item)
            if (bitmap != null) {
                itemImageView.setImageBitmap(bitmap)
                itemImageView.visibility = View.VISIBLE
                imagePlaceholder.visibility = View.GONE
            }
        }
        updatePhotoButtonText()
    }

    /**
     * Handles the result from gallery selection.
     */
    fun handleGalleryResult(uri: Uri?) {
        if (uri != null) {
            selectedImageUri = uri
            updateImagePreview()
        }
    }

    /**
     * Handles the result from camera capture.
     */
    fun handleCameraResult(success: Boolean) {
        if (success && selectedImageUri != null) {
            updateImagePreview()
        }
    }

    /**
     * Handles camera permission result.
     */
    fun handlePermissionResult(granted: Boolean) {
        if (granted) {
            takePicture()
        } else {
            Toast.makeText(
                activity,
                activity.getString(R.string.error_camera_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Shows the image source selection dialog.
     */
    fun showImageSourceDialog() {
        val hasImage = hasImage()

        val options = if (hasImage) {
            arrayOf("Take Photo", "Choose from Gallery", "Remove Photo")
        } else {
            arrayOf("Take Photo", "Choose from Gallery")
        }

        val title = if (hasImage) "Edit Picture" else "Add Picture"

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(options) { _, which ->
                when {
                    which == 0 -> takePicture()
                    which == 1 -> selectFromGallery()
                    which == 2 && hasImage -> removeImage()
                }
            }
            .show()
    }

    /**
     * Removes the current image.
     */
    fun removeImage() {
        selectedImageUri = null
        itemImageView.setImageBitmap(null)
        itemImageView.visibility = View.VISIBLE
        imagePlaceholder.visibility = View.VISIBLE
        updatePhotoButtonText()

        currentItem?.let { item ->
            if (item.imagePath != null) {
                itemManager.deleteItemImage(item)
            }
        }
    }

    private fun selectFromGallery() {
        selectGalleryLauncher.launch("image/*")
    }

    private fun takePicture() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQUEST_IMAGE_CAPTURE)
            return
        }

        try {
            val photoFile = createImageFile()
            selectedImageUri = FileProvider.getUriForFile(
                activity,
                "com.electricdreams.shellshock.fileprovider",
                photoFile,
            )
            takePictureLauncher.launch(selectedImageUri)
        } catch (ex: IOException) {
            Toast.makeText(
                activity,
                activity.getString(R.string.error_error_creating_image_file, ex.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val image = File.createTempFile(imageFileName, ".jpg", storageDir)
        currentPhotoPath = image.absolutePath
        return image
    }

    private fun updateImagePreview() {
        selectedImageUri?.let { uri ->
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(activity.contentResolver, uri)
                itemImageView.setImageBitmap(bitmap)
                itemImageView.visibility = View.VISIBLE
                imagePlaceholder.visibility = View.GONE
                updatePhotoButtonText()
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.error_failed_to_load_image),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updatePhotoButtonText() {
        val hasImage = hasImage()
        addImageButton.text = if (hasImage) "Edit Photo" else "Add Photo"
    }
}
