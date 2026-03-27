package com.electricdreams.numo.feature.onboarding

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Illustration for the "Automatic self-custody" explainer slide.
 * A stack of light-mode banner notifications — two faded/scaled cards behind
 * the main front card, creating a "latest in a series" effect.
 */
class AutoCustodyIllustration : Drawable() {

    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val bannerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20000000")
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    private val orangeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF0DB")
        style = Paint.Style.FILL
    }

    private val lightningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7931A")
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A2540")
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6F6F73")
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val cx = b.left + w / 2f
        val cy = b.top + h / 2f

        val scale = w / 380f

        val bannerW = 340f * scale
        val bannerH = 82f * scale
        val bannerR = 14f * scale

        // ── Back card (3rd) — smallest, most faded, furthest up ──
        canvas.save()
        val scale3 = 0.88f
        canvas.translate(cx, cy - 22f * scale)
        canvas.scale(scale3, scale3)
        drawCardOnly(canvas, bannerW, bannerH, bannerR, scale, alpha = 0.20f)
        canvas.restore()

        // ── Middle card (2nd) — slightly smaller, faded ──
        canvas.save()
        val scale2 = 0.94f
        canvas.translate(cx, cy - 10f * scale)
        canvas.scale(scale2, scale2)
        drawCardOnly(canvas, bannerW, bannerH, bannerR, scale, alpha = 0.45f)
        canvas.restore()

        // ── Front card (1st) — full size, full content ──
        drawMainBanner(canvas, cx, cy + 6f * scale, bannerW, bannerH, bannerR, scale)
    }

    /** Draws just the white card shape — no content, used for background stack. */
    private fun drawCardOnly(
        canvas: Canvas, w: Float, h: Float, r: Float, scale: Float, alpha: Float
    ) {
        val rect = RectF(-w / 2, -h / 2, w / 2, h / 2)

        // Shadow
        bannerShadowPaint.alpha = (alpha * 50).toInt()
        canvas.drawRoundRect(
            RectF(rect.left + 2f * scale, rect.top + 4f * scale,
                rect.right + 2f * scale, rect.bottom + 4f * scale),
            r, r, bannerShadowPaint
        )
        bannerShadowPaint.alpha = 0x20 // restore default

        // Card
        bannerPaint.alpha = (alpha * 255).toInt()
        canvas.drawRoundRect(rect, r, r, bannerPaint)
        bannerPaint.alpha = 255
    }

    /** Draws the front banner with full content. */
    private fun drawMainBanner(
        canvas: Canvas, cx: Float, cy: Float,
        w: Float, h: Float, r: Float, scale: Float
    ) {
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

        // Shadow
        canvas.drawRoundRect(
            RectF(rect.left + 2f * scale, rect.top + 8f * scale,
                rect.right + 2f * scale, rect.bottom + 8f * scale),
            r, r, bannerShadowPaint
        )

        // Card
        canvas.drawRoundRect(rect, r, r, bannerPaint)

        // Lightning icon background
        val iconSize = 48f * scale
        val iconR = 12f * scale
        val iconX = rect.left + 18f * scale
        val iconY = cy - iconSize / 2f
        val iconRect = RectF(iconX, iconY, iconX + iconSize, iconY + iconSize)
        canvas.drawRoundRect(iconRect, iconR, iconR, orangeBgPaint)

        // Lightning bolt
        val boltCx = iconX + iconSize / 2f
        val boltCy = iconY + iconSize / 2f
        val boltPath = Path().apply {
            moveTo(boltCx + 1.5f * scale, boltCy - 12f * scale)
            lineTo(boltCx - 7f * scale, boltCy + 1f * scale)
            lineTo(boltCx - 0.5f * scale, boltCy + 1f * scale)
            lineTo(boltCx - 1.5f * scale, boltCy + 12f * scale)
            lineTo(boltCx + 7f * scale, boltCy - 1f * scale)
            lineTo(boltCx + 0.5f * scale, boltCy - 1f * scale)
            close()
        }
        canvas.drawPath(boltPath, lightningPaint)

        // Title
        val textX = iconX + iconSize + 16f * scale
        titlePaint.textSize = 16f * scale
        canvas.drawText("Threshold Reached", textX, cy - 5f * scale, titlePaint)

        // Subtitle
        subtitlePaint.textSize = 13f * scale
        canvas.drawText("\$50.00 sent to satoshi@alby.com", textX, cy + 16f * scale, subtitlePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = 400
    override fun getIntrinsicHeight(): Int = 250
}
