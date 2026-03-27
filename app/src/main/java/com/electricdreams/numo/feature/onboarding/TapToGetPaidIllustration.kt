package com.electricdreams.numo.feature.onboarding

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Illustration for the "Tap to get paid" explainer slide.
 * Two phones in landscape-ish arrangement entering from left and right sides,
 * meeting in the center with NFC waves between them.
 * Uses the same phone rendering style as [TapToPayIllustration].
 */
class TapToGetPaidIllustration : Drawable() {

    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06131F")
        style = Paint.Style.FILL
    }

    private val phoneEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C3550")
        style = Paint.Style.STROKE
    }

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B2038")
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    private val nfcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EFFC2")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EFFC2")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f

        val scale = w / 380f

        val phoneW = 120f * scale
        val phoneH = 240f * scale
        val phoneR = 20f * scale

        // ── Left phone (merchant) — nearly horizontal, top facing right ──
        canvas.save()
        canvas.translate(cx - 70f * scale, cy + 15f * scale)
        canvas.rotate(75f)
        drawPhone(canvas, phoneW, phoneH, phoneR, scale, isMerchant = true)
        canvas.restore()

        // ── NFC waves centered between phone tops ──
        drawNfcWaves(canvas, cx, cy - 20f * scale, scale)

        // ── Right phone (customer) — nearly horizontal, top facing left ──
        canvas.save()
        canvas.translate(cx + 70f * scale, cy - 15f * scale)
        canvas.rotate(-75f)
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
            drawMerchantScreen(canvas, screenCx, screenCy, scale)
        } else {
            drawCustomerScreen(canvas, screenCx, screenCy, scale)
        }

        // Home indicator
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

    private fun drawMerchantScreen(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        // Checkmark circle — centered on screen
        val checkCy = cy + 2f * scale
        val circleR = 18f * scale
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

        // Label
        sublabelPaint.textSize = 9f * scale
        sublabelPaint.color = Color.parseColor("#60FFFFFF")
        canvas.drawText("Payment received", cx, checkCy + 34f * scale, sublabelPaint)
    }

    private fun drawCustomerScreen(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        // Contactless icon
        nfcPaint.strokeWidth = 1.8f * scale
        val iconCy = cy - 30f * scale
        for (i in 0..2) {
            val arcR = (7f + i * 6f) * scale
            nfcPaint.alpha = 200 - i * 55
            val arcRect = RectF(cx - arcR, iconCy - arcR, cx + arcR, iconCy + arcR)
            canvas.drawArc(arcRect, -55f, 110f, false, nfcPaint)
        }
        nfcPaint.alpha = 255

        // Amount
        amountPaint.textSize = 22f * scale
        canvas.drawText("\$5.50", cx, cy + 6f * scale, amountPaint)

        // Label
        labelPaint.textSize = 10f * scale
        canvas.drawText("Tap to pay", cx, cy + 24f * scale, labelPaint)

        // Merchant name
        sublabelPaint.textSize = 8f * scale
        sublabelPaint.color = Color.parseColor("#40FFFFFF")
        canvas.drawText("Bakery", cx, cy + 44f * scale, sublabelPaint)
    }

    private fun drawNfcWaves(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        nfcPaint.strokeWidth = 2.2f * scale
        val alphas = intArrayOf(140, 90, 45)
        for (i in 0..2) {
            val r = (18f + i * 13f) * scale
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
