package com.electricdreams.numo.feature.items.handlers

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.ItemManager
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
    
    /** The corrected bitmap ready to be saved (with proper rotation applied) */
    var correctedBitmap: Bitmap? = null
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
            updateImagePreview(fromCamera = true)
        }
    }

    /**
     * Handles camera permission result.
     */
    fun handlePermissionResult(granted: Boolean) {
        if (granted) {
            takePicture()
        } else {
            Toast.makeText(activity, R.string.item_list_toast_camera_permission, Toast.LENGTH_SHORT).show()
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
                "com.electricdreams.numo.fileprovider",
                photoFile,
            )
            takePictureLauncher.launch(selectedImageUri)
        } catch (ex: IOException) {
            Toast.makeText(activity, activity.getString(R.string.item_list_toast_error_creating_image, ex.message), Toast.LENGTH_SHORT).show()
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

    private fun updateImagePreview(fromCamera: Boolean = false) {
        selectedImageUri?.let { uri ->
            try {
                val bitmap = com.electricdreams.numo.util.getBitmapCompat(activity.contentResolver, uri)
                correctedBitmap = correctImageRotation(uri, bitmap, fromCamera)
                itemImageView.setImageBitmap(correctedBitmap)
                itemImageView.visibility = View.VISIBLE
                imagePlaceholder.visibility = View.GONE
                updatePhotoButtonText()
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.item_list_toast_failed_load_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Corrects image rotation based on EXIF orientation data.
     * Camera images often have EXIF metadata indicating rotation that needs to be applied.
     */
    private fun correctImageRotation(uri: Uri, bitmap: Bitmap, fromCamera: Boolean): Bitmap {
        return try {
            val inputStream = activity.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            // For camera images, apply rotation based on EXIF. 
            // EXIF ORIENTATION_ROTATE_X means the image needs X degrees clockwise rotation to display correctly.
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_NORMAL -> if (fromCamera) 90f else 0f  // Camera with no EXIF often needs 90° fix
                else -> if (fromCamera) 90f else 0f
            }

            if (rotationDegrees == 0f) {
                bitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            bitmap // Return original bitmap if rotation correction fails
        }
    }

    private fun updatePhotoButtonText() {
        val hasImage = hasImage()
        addImageButton.text = if (hasImage) "Edit Photo" else "Add Photo"
    }
}
