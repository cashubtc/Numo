package com.electricdreams.numo.feature.items

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
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
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activity for scanning barcodes using CameraX and ML Kit.
 * Returns the scanned barcode value as a result.
 */
class BarcodeScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BarcodeScanner"
        private const val REQUEST_CAMERA_PERMISSION = 1001
        const val EXTRA_BARCODE_VALUE = "barcode_value"
        const val EXTRA_BARCODE_FORMAT = "barcode_format"
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: View
    private lateinit var instructionText: TextView
    private lateinit var closeButton: ImageButton

    private lateinit var cameraExecutor: ExecutorService
    private var barcodeScanner: BarcodeScanner? = null
    private var isScanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barcode_scanner)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.scanner_overlay)
        instructionText = findViewById(R.id.instruction_text)
        closeButton = findViewById(R.id.close_button)

        closeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure barcode scanner for all common barcode formats
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
                        if (!isScanning) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        @androidx.camera.core.ExperimentalGetImage
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            barcodeScanner?.process(image)
                                ?.addOnSuccessListener { barcodes ->
                                    if (barcodes.isNotEmpty() && isScanning) {
                                        val barcode = barcodes.first()
                                        barcode.rawValue?.let { value ->
                                            isScanning = false
                                            onBarcodeDetected(value, barcode.format)
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

    private fun onBarcodeDetected(value: String, format: Int) {
        runOnUiThread {
            // Provide haptic feedback
            previewView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)

            val intent = Intent().apply {
                putExtra(EXTRA_BARCODE_VALUE, value)
                putExtra(EXTRA_BARCODE_FORMAT, formatToString(format))
            }
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun formatToString(format: Int): String {
        return when (format) {
            Barcode.FORMAT_EAN_13 -> getString(R.string.barcode_format_ean_13)
            Barcode.FORMAT_EAN_8 -> getString(R.string.barcode_format_ean_8)
            Barcode.FORMAT_UPC_A -> getString(R.string.barcode_format_upc_a)
            Barcode.FORMAT_UPC_E -> getString(R.string.barcode_format_upc_e)
            Barcode.FORMAT_CODE_128 -> getString(R.string.barcode_format_code_128)
            Barcode.FORMAT_CODE_39 -> getString(R.string.barcode_format_code_39)
            Barcode.FORMAT_CODE_93 -> getString(R.string.barcode_format_code_93)
            Barcode.FORMAT_ITF -> getString(R.string.barcode_format_itf)
            Barcode.FORMAT_CODABAR -> getString(R.string.barcode_format_codabar)
            Barcode.FORMAT_QR_CODE -> getString(R.string.barcode_format_qr_code)
            Barcode.FORMAT_DATA_MATRIX -> getString(R.string.barcode_format_data_matrix)
            else -> getString(R.string.barcode_format_unknown)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner?.close()
    }
}
