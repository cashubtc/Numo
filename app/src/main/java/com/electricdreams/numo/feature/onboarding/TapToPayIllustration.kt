package com.electricdreams.numo.feature.onboarding

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Premium illustration of two phones doing NFC tap-to-pay.
 * Phones are large, overlapping, and intentionally extend beyond the bottom
 * of the drawable bounds so the parent container clips them for a dynamic feel.
 */
class TapToPayIllustration : Drawable() {

    // Phone body — very dark navy with subtle edge highlight
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06131F")
        style = Paint.Style.FILL
    }

    private val phoneEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C3550")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    // Screen — slightly lighter than body
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B2038")
        style = Paint.Style.FILL
    }

    // Screen inner glow (subtle gradient overlay)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Text paints
    private val amountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EFFC2")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val sublabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // NFC arcs
    private val nfcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EFFC2")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Checkmark
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EFFC2")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Status bar dots
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val cx = b.left + w / 2f
        val top = b.top.toFloat()

        val scale = w / 380f // normalize to a 380-wide design

        // Phones are tall — they intentionally extend past the bottom
        val phoneW = 140f * scale
        val phoneH = 280f * scale
        val phoneR = 22f * scale

        // Position phones so tops are visible but bottoms clip
        val phoneCenterY = top + h * 0.55f

        // ── Left phone (merchant) — tilted clockwise ──
        canvas.save()
        canvas.translate(cx - 48f * scale, phoneCenterY)
        canvas.rotate(10f)
        drawPhone(canvas, phoneW, phoneH, phoneR, scale, isMerchant = true)
        canvas.restore()

        // ── NFC waves between phones ──
        drawNfcWaves(canvas, cx + 8f * scale, top + h * 0.32f, scale)

        // ── Right phone (customer) — tilted counter-clockwise ──
        canvas.save()
        canvas.translate(cx + 48f * scale, phoneCenterY - 20f * scale)
        canvas.rotate(-10f)
        drawPhone(canvas, phoneW, phoneH, phoneR, scale, isMerchant = false)
        canvas.restore()
    }

    private fun drawPhone(
        canvas: Canvas, w: Float, h: Float, r: Float,
        scale: Float, isMerchant: Boolean
    ) {
        val x = -w / 2f
        val y = -h / 2f

        // Shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#20000000")
            maskFilter = BlurMaskFilter(12f * scale, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(x + 4f * scale, y + 6f * scale, x + w + 4f * scale, y + h + 6f * scale),
            r, r, shadowPaint
        )

        // Phone body
        val phoneRect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(phoneRect, r, r, phonePaint)

        // Edge highlight
        phoneEdgePaint.strokeWidth = 1.2f * scale
        canvas.drawRoundRect(phoneRect, r, r, phoneEdgePaint)

        // Screen area
        val bezel = 5f * scale
        val topBezel = 20f * scale
        val bottomBezel = 14f * scale
        val sx = x + bezel
        val sy = y + topBezel
        val sw = w - bezel * 2
        val sh = h - topBezel - bottomBezel
        val sr = (r - 3f * scale).coerceAtLeast(8f * scale)
        val screenRect = RectF(sx, sy, sx + sw, sy + sh)
        canvas.drawRoundRect(screenRect, sr, sr, screenPaint)

        // Subtle screen glow from top
        val glowShader = LinearGradient(
            sx, sy, sx, sy + sh * 0.4f,
            Color.parseColor("#0D5EFFC2"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = glowShader
        canvas.drawRoundRect(screenRect, sr, sr, glowPaint)
        glowPaint.shader = null

        // Dynamic island
        val islandW = 32f * scale
        val islandH = 8f * scale
        val islandX = x + (w - islandW) / 2f
        val islandY = y + 8f * scale
        val islandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#030A12")
        }
        canvas.drawRoundRect(
            RectF(islandX, islandY, islandX + islandW, islandY + islandH),
            islandH / 2, islandH / 2, islandPaint
        )

        // Screen content
        val screenCx = sx + sw / 2f
        val screenCy = sy + sh * 0.38f

        if (isMerchant) {
            drawMerchantScreen(canvas, screenCx, screenCy, sw, scale)
        } else {
            drawCustomerScreen(canvas, screenCx, screenCy, sw, scale)
        }

        // Status bar indicators (subtle dots top-right of screen)
        dotPaint.alpha = 40
        val dotY = sy + 10f * scale
        for (i in 0..2) {
            canvas.drawCircle(sx + sw - (12f + i * 8f) * scale, dotY, 2f * scale, dotPaint)
        }
        dotPaint.alpha = 255

        // Home indicator at bottom
        val homePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#30FFFFFF")
        }
        val homeW = 36f * scale
        val homeH = 3.5f * scale
        val homeX = x + (w - homeW) / 2f
        val homeY = y + h - bottomBezel / 2f - homeH / 2f
        canvas.drawRoundRect(
            RectF(homeX, homeY, homeX + homeW, homeY + homeH),
            homeH / 2, homeH / 2, homePaint
        )
    }

    private fun drawMerchantScreen(
        canvas: Canvas, cx: Float, cy: Float, screenW: Float, scale: Float
    ) {
        // Amount
        amountPaint.textSize = 36f * scale
        canvas.drawText("\$20", cx, cy, amountPaint)

        // Checkmark circle
        val checkCy = cy + 36f * scale
        val circleR = 16f * scale
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A5EFFC2")
        }
        canvas.drawCircle(cx, checkCy, circleR, circlePaint)

        // Checkmark
        checkPaint.strokeWidth = 2.5f * scale
        val checkPath = Path().apply {
            moveTo(cx - 7f * scale, checkCy)
            lineTo(cx - 1.5f * scale, checkCy + 6f * scale)
            lineTo(cx + 8f * scale, checkCy - 5f * scale)
        }
        canvas.drawPath(checkPath, checkPaint)

        // "Payment received" label
        sublabelPaint.textSize = 9f * scale
        sublabelPaint.color = Color.parseColor("#60FFFFFF")
        canvas.drawText("Payment received", cx, checkCy + 32f * scale, sublabelPaint)
    }

    private fun drawCustomerScreen(
        canvas: Canvas, cx: Float, cy: Float, screenW: Float, scale: Float
    ) {
        // Contactless icon at top
        nfcPaint.strokeWidth = 1.8f * scale
        val iconCy = cy - 36f * scale
        for (i in 0..2) {
            val arcR = (7f + i * 6f) * scale
            nfcPaint.alpha = 200 - i * 55
            val arcRect = RectF(cx - arcR, iconCy - arcR, cx + arcR, iconCy + arcR)
            canvas.drawArc(arcRect, -55f, 110f, false, nfcPaint)
        }
        nfcPaint.alpha = 255

        // Amount
        amountPaint.textSize = 36f * scale
        canvas.drawText("\$20", cx, cy + 4f * scale, amountPaint)

        // "Tap to pay" label
        labelPaint.textSize = 11f * scale
        canvas.drawText("Tap to pay", cx, cy + 28f * scale, labelPaint)

        // Subtle merchant name
        sublabelPaint.textSize = 8f * scale
        sublabelPaint.color = Color.parseColor("#40FFFFFF")
        canvas.drawText("Coffee Shop", cx, cy + 46f * scale, sublabelPaint)
    }

    private fun drawNfcWaves(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        nfcPaint.strokeWidth = 2.2f * scale
        val alphas = intArrayOf(140, 90, 45)
        for (i in 0..2) {
            val r = (20f + i * 14f) * scale
            nfcPaint.alpha = alphas[i]
            val arcRect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arcRect, -40f, 80f, false, nfcPaint)
        }
        nfcPaint.alpha = 255
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = 400
    override fun getIntrinsicHeight(): Int = 340
}
