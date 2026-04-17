package com.electricdreams.numo.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that fades to transparent towards the bottom via alpha compositing.
 * Works regardless of background color / theme.
 */
class FadingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) { super.onDraw(canvas); return }

        val save = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)

        fadePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fadePaint)
        canvas.restoreToCount(save)
    }
}
