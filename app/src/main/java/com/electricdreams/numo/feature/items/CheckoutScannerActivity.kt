package com.electricdreams.numo.feature.items

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.ItemManager
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Barcode scanner for checkout mode - adds items to basket without closing.
 */
class CheckoutScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: View
    private lateinit var instructionText: TextView
    private lateinit var topCloseButton: ImageButton
    private lateinit var closeButton: Button
    private lateinit var scannedItemOverlay: LinearLayout

    // Item overlay views
    private lateinit var itemImageContainer: View
    private lateinit var itemImage: ImageView
    private lateinit var itemImagePlaceholder: ImageView
    private lateinit var itemName: TextView
    private lateinit var itemVariation: TextView
    private lateinit var itemPrice: TextView
    private lateinit var itemQuantity: TextView
    private lateinit var decreaseButton: ImageButton
    private lateinit var increaseButton: ImageButton

    private lateinit var cameraExecutor: ExecutorService
    private var barcodeScanner: BarcodeScanner? = null

    private lateinit var itemManager: ItemManager
    private lateinit var basketManager: BasketManager

    private var currentItem: Item? = null
    private var currentQuantity: Int = 0
    private var lastScannedGtin: String? = null
    private var lastScanTime: Long = 0
    private var barcodeLeftView = true
    private var basketUpdated = false
    
    companion object {
        private const val TAG = "CheckoutScanner"
        private const val REQUEST_CAMERA_PERMISSION = 1001
        const val RESULT_BASKET_UPDATED = 1002
        private const val SAME_BARCODE_COOLDOWN_MS = 2000L // 2 second cooldown for same barcode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barcode_scanner_checkout)

        itemManager = ItemManager.getInstance(this)
        basketManager = BasketManager.getInstance()

        initViews()
        setupListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()

        barcodeScanner = BarcodeScanning.getClient(options)

        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.scanner_overlay)
        instructionText = findViewById(R.id.instruction_text)
        topCloseButton = findViewById(R.id.top_close_button)
        closeButton = findViewById(R.id.close_button)
        scannedItemOverlay = findViewById(R.id.scanned_item_overlay)

        itemImageContainer = findViewById(R.id.item_image_container)
        itemImage = findViewById(R.id.item_image)
        itemImagePlaceholder = findViewById(R.id.item_image_placeholder)
        itemName = findViewById(R.id.item_name)
        itemVariation = findViewById(R.id.item_variation)
        itemPrice = findViewById(R.id.item_price)
        itemQuantity = findViewById(R.id.item_quantity)
        decreaseButton = findViewById(R.id.decrease_button)
        increaseButton = findViewById(R.id.increase_button)
    }

    private fun setupListeners() {
        topCloseButton.setOnClickListener {
            if (basketUpdated) {
                setResult(RESULT_BASKET_UPDATED)
            } else {
                setResult(RESULT_CANCELED)
            }
            finish()
        }

        closeButton.setOnClickListener {
            if (basketUpdated) {
                setResult(RESULT_BASKET_UPDATED)
            } else {
                setResult(RESULT_CANCELED)
            }
            finish()
        }

        decreaseButton.setOnClickListener {
            if (currentQuantity > 0) {
                currentQuantity--
                updateQuantityDisplay()
                updateBasketForCurrentItem()
            }
        }

        increaseButton.setOnClickListener {
            val item = currentItem ?: return@setOnClickListener
            // Check stock if tracking inventory
            val hasStock = if (item.trackInventory) {
                item.quantity > currentQuantity
            } else {
                true
            }

            if (hasStock) {
                currentQuantity++
                updateQuantityDisplay()
                updateBasketForCurrentItem()
            } else {
                Toast.makeText(this, "No more stock available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateQuantityDisplay() {
        itemQuantity.text = currentQuantity.toString()
        decreaseButton.isEnabled = currentQuantity > 0
        decreaseButton.alpha = if (currentQuantity > 0) 1f else 0.4f
    }

    private fun updateBasketForCurrentItem() {
        val item = currentItem ?: return

        if (currentQuantity <= 0) {
            basketManager.removeItem(item.id!!)
        } else {
            val updated = basketManager.updateItemQuantity(item.id!!, currentQuantity)
            if (!updated) {
                basketManager.addItem(item, currentQuantity)
            }
        }
        basketUpdated = true
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.barcode_scanner_error_camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        @androidx.camera.core.ExperimentalGetImage
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            barcodeScanner?.process(image)
                                ?.addOnSuccessListener { barcodes ->
                                    if (barcodes.isNotEmpty()) {
                                        val barcode = barcodes.first()
                                        barcode.rawValue?.let { value ->
                                            onBarcodeDetected(value)
                                        }
                                    }
                                }
                                ?.addOnFailureListener { e ->
                                    Log.e(TAG, "Barcode scanning failed: ${e.message}")
                                }
                                ?.addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodeDetected(gtin: String) {
        val currentTime = System.currentTimeMillis()
        
        // Check if this is the same barcode and we're within cooldown period
        if (gtin == lastScannedGtin && currentTime - lastScanTime < SAME_BARCODE_COOLDOWN_MS) {
            // Still in cooldown period, ignore this scan (no vibration)
            return
        }
        
        // Check if this is the same barcode after cooldown has passed
        if (gtin == lastScannedGtin) {
            // Cooldown passed - increment quantity
            if (currentItem != null) {
                lastScanTime = currentTime
                runOnUiThread {
                    val item = currentItem ?: return@runOnUiThread
                    val hasStock = if (item.trackInventory) {
                        item.quantity > currentQuantity
                    } else {
                        true
                    }

                    if (hasStock) {
                        currentQuantity++
                        updateQuantityDisplay()
                        updateBasketForCurrentItem()
                        // Haptic feedback for successful quantity increment
                        previewView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    }
                }
                return
            }
        }

        // Different barcode - find item by Gtin
        val item = itemManager.findItemByGtin(gtin)
        if (item == null) {
            // No haptic feedback for unknown items during cooldown
            return
        }

        lastScannedGtin = gtin
        lastScanTime = currentTime
        currentItem = item

        // Get current basket quantity for this item
        val basketItem = basketManager.getBasketItems().find { it.item.id == item.id }
        currentQuantity = (basketItem?.quantity ?: 0) + 1

        runOnUiThread {
            showItemOverlay(item)
            updateBasketForCurrentItem()
            // Haptic feedback ONLY for successful scans (not during cooldown)
            previewView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun showItemOverlay(item: Item) {
        // Update item info
        itemName.text = item.name ?: ""

        if (!item.variationName.isNullOrEmpty()) {
            itemVariation.text = item.variationName
            itemVariation.visibility = View.VISIBLE
        } else {
            itemVariation.visibility = View.GONE
        }

        itemPrice.text = item.getFormattedPrice()
        updateQuantityDisplay()

        // Load image
        if (!item.imagePath.isNullOrEmpty()) {
            val imageFile = File(item.imagePath!!)
            if (imageFile.exists()) {
                val bitmap: Bitmap? = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (bitmap != null) {
                    itemImage.setImageBitmap(bitmap)
                    itemImagePlaceholder.visibility = View.GONE
                } else {
                    itemImage.setImageBitmap(null)
                    itemImagePlaceholder.visibility = View.VISIBLE
                }
            } else {
                itemImage.setImageBitmap(null)
                itemImagePlaceholder.visibility = View.VISIBLE
            }
        } else {
            itemImage.setImageBitmap(null)
            itemImagePlaceholder.visibility = View.VISIBLE
        }

        // Animate in if not already visible
        if (scannedItemOverlay.visibility != View.VISIBLE) {
            instructionText.visibility = View.GONE
            scannedItemOverlay.visibility = View.VISIBLE

            // Slide and fade in animation
            scannedItemOverlay.translationY = 300f
            scannedItemOverlay.alpha = 0f

            val slideUp = ObjectAnimator.ofFloat(scannedItemOverlay, "translationY", 300f, 0f)
            val fadeIn = ObjectAnimator.ofFloat(scannedItemOverlay, "alpha", 0f, 1f)

            AnimatorSet().apply {
                playTogether(slideUp, fadeIn)
                duration = 350
                interpolator = DecelerateInterpolator()
                start()
            }
        } else {
            // Already visible - just pulse the quantity
            val scaleUp = ObjectAnimator.ofFloat(itemQuantity, "scaleX", 1f, 1.3f)
            val scaleUpY = ObjectAnimator.ofFloat(itemQuantity, "scaleY", 1f, 1.3f)
            val scaleDown = ObjectAnimator.ofFloat(itemQuantity, "scaleX", 1.3f, 1f)
            val scaleDownY = ObjectAnimator.ofFloat(itemQuantity, "scaleY", 1.3f, 1f)

            AnimatorSet().apply {
                play(scaleUp).with(scaleUpY)
                play(scaleDown).with(scaleDownY).after(scaleUp)
                duration = 100
                start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner?.close()
    }

    override fun onBackPressed() {
        if (basketUpdated) {
            setResult(RESULT_BASKET_UPDATED)
        }
        super.onBackPressed()
    }
}
