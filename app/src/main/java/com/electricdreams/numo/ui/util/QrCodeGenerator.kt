package com.electricdreams.numo.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Utility class for generating QR code bitmaps.
 * 
 * Generates visually appealing QR codes with rounded dots instead of squares.
 */
object QrCodeGenerator {

    /**
     * Generate a QR code bitmap for the given text.
     *
     * @param text The content to encode in the QR code
     * @param size The desired size of the output bitmap in pixels
     * @param foregroundColor The color of the QR dots
     * @param backgroundColor The color of the background
     * @return A bitmap containing the QR code with rounded dots
     * @throws Exception if encoding fails
     */
    @Throws(Exception::class)
    fun generate(
        text: String, 
        size: Int, 
        foregroundColor: Int = 0xFF000000.toInt(), 
        backgroundColor: Int = 0xFFFFFFFF.toInt()
    ): Bitmap {
        val hints: MutableMap<EncodeHintType, Any> = mutableMapOf()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L
        hints[EncodeHintType.MARGIN] = 1 // Small margin so dots aren't cut off

        val qrWriter = QRCodeWriter()
        val rawMatrix: BitMatrix = qrWriter.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)

        val matrixWidth = rawMatrix.width
        val matrixHeight = rawMatrix.height

        val scale = size / matrixWidth
        val outputWidth = matrixWidth * scale
        val outputHeight = matrixHeight * scale

        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(backgroundColor)

        val paint = Paint().apply {
            color = foregroundColor
            isAntiAlias = true
        }

        val radius = scale.toFloat() / 2f

        for (x in 0 until matrixWidth) {
            for (y in 0 until matrixHeight) {
                if (rawMatrix[x, y]) {
                    val cx = x * scale + radius
                    val cy = y * scale + radius
                    canvas.drawCircle(cx, cy, radius, paint)
                }
            }
        }

        return outputBitmap
    }
}

