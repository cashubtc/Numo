package com.electricdreams.numo.feature.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.animation.RollingAmountPainter
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Four authored product illustrations. Pure rendering: no wallet, network, or independent clocks. */
class OnboardingSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var page: Int = 0
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var timeMillis: Long = 0L
        set(value) { if (field != value) { field = value; invalidate() } }

    internal var checkoutPreview: CheckoutPreviewScreens? = null
    private val phoneFrame by lazy(LazyThreadSafetyMode.NONE) {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_phone_frame)
    }
    private val iphoneFrame by lazy(LazyThreadSafetyMode.NONE) {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_iphone_frame)
    }
    private val customerWallet by lazy(LazyThreadSafetyMode.NONE) {
        BitmapFactory.decodeResource(resources, R.drawable.onboarding_customer_wallet)
    }
    private val panelText = color(R.color.onboarding_tour_panel_text)
    private val panelSecondary = color(R.color.onboarding_tour_panel_secondary)
    private val iconSurface = Color.rgb(242, 244, 246)
    private val surface = color(R.color.onboarding_tour_surface)
    private val border = color(R.color.onboarding_tour_border)
    private val white = color(R.color.color_text_on_dark)
    private val green = color(R.color.onboarding_tour_accent)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val chartSegment = Path()
    private val chartMeasure = PathMeasure()
    private val chartTip = FloatArray(2)
    private val rect = RectF()
    private val notchRadii = floatArrayOf(0f, 0f, 0f, 0f, 32f, 32f, 32f, 32f)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val numberPainters = List(8) { RollingAmountPainter() }
    private val locale = resources.configuration.locales[0] ?: Locale.getDefault()
    private val decimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    private val integerFormat = NumberFormat.getIntegerInstance(locale)
    // A fixed illustrative rate keeps both representations consistent, without fetching a price.
    private val fiatFormat = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    private val coffeeLabel = context.getString(R.string.onboarding_tour_coffee)
    private val shirtLabel = context.getString(R.string.onboarding_tour_shirt)
    private val toteLabel = context.getString(R.string.onboarding_tour_tote)
    private val autoLabel = context.getString(R.string.onboarding_tour_auto_withdraw)
    private val toWalletLabel = context.getString(R.string.onboarding_tour_to_wallet)
    private val sentLabel = context.getString(R.string.onboarding_tour_sent)
    private val todayLabel = context.getString(R.string.onboarding_tour_today)
    private val addItemLabel = context.getString(R.string.onboarding_tour_add_item)
    private val productLabels = listOf(coffeeLabel, shirtLabel, toteLabel)
    private val productFiat = OnboardingAmounts.productCents.map { fiatFormat.format(it / 100.0) }
    private val productSats = OnboardingAmounts.productCents.map { sats(OnboardingAmounts.sats(it)) }
    // Uneven, authored offsets read naturally and replay consistently without per-frame randomness.
    private val productRollStarts = longArrayOf(650L, 2260L, 1370L)
    private val productRollDurations = longArrayOf(1120L, 1280L, 940L)
    private val zeroFiat = fiatFormat.format(0)
    private val zeroSats = sats(0)
    private val notificationAmounts = OnboardingAmounts.withdrawalCents.map { sats(OnboardingAmounts.sats(it)) }
    private val notificationStarts = longArrayOf(330L, 1000L, 1530L, 2140L, 2610L, 3170L, 3680L)
    private val salesFiat = OnboardingAmounts.salesCents.map { fiatFormat.format(it / 100.0) }
    private val salesSats = OnboardingAmounts.salesCents.map { sats(OnboardingAmounts.sats(it)) }
    private val paymentCounts = listOf(18, 21, 24).map {
        resources.getQuantityString(R.plurals.onboarding_tour_payments, it, it)
    }
    // Uneven intraday activity: a quiet opening, lunch burst, lull, then a late pickup.
    private val chartTimes = floatArrayOf(0f, .035f, .075f, .13f, .18f, .23f, .255f, .30f,
        .34f, .38f, .415f, .45f, .51f, .57f, .615f, .65f, .71f, .76f, .79f, .84f, .91f, .95f, 1f)
    private val chartStart = floatArrayOf(.09f, .10f, .09f, .16f, .16f, .14f, .26f, .23f,
        .40f, .34f, .44f, .39f, .37f, .47f, .45f, .39f, .57f, .62f, .54f, .67f, .72f, .69f, .80f)
    private val chartEnd = floatArrayOf(.10f, .11f, .10f, .18f, .18f, .16f, .30f, .25f,
        .49f, .42f, .54f, .46f, .43f, .55f, .52f, .44f, .68f, .72f, .61f, .77f, .81f, .76f, .87f)
    private val sceneHeight: Float get() = when (page) { 1 -> 380f; 2 -> 220f; else -> 320f }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (min(width.toFloat(), 400f * resources.displayMetrics.density) * (sceneHeight / 360f)).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 360f, height / sceneHeight)
        val saved = canvas.save()
        canvas.translate((width - 360 * scale) / 2f, (height - sceneHeight * scale) / 2f)
        canvas.scale(scale, scale)
        if (page == 1) {
            drawTap(canvas)
        } else {
            when (page) {
                0 -> drawProducts(canvas)
                2 -> drawWithdrawals(canvas)
                else -> drawSales(canvas)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun drawProducts(canvas: Canvas) {
        for (index in 0..3) {
            val enter = ease(timeMillis, index * 90L, 800)
            val x = if (index % 2 == 0) 8f else 188f
            val y = if (index < 2) 25f else 167f
            val save = canvas.saveLayerAlpha(0f, 0f, 360f, 320f, (enter * 255).toInt())
            canvas.translate(x, y + (1f - enter) * 18f)
            // Cards arrive with a little body, not just a slide: scale from the card's center.
            val grow = .965f + .035f * enter
            canvas.scale(grow, grow, 82f, 63f)
            rounded(canvas, 0f, 0f, 164f, 126f, 19f, surface)
            outline(canvas, 0f, 0f, 164f, 126f, 19f, border)
            if (index < 3) {
                rounded(canvas, 14f, 14f, 32f, 32f, 10f, iconSurface)
                productIcon(canvas, index, 20f, 20f)
                label(canvas, productLabels[index], 54f, 35f, 13f, panelText, true, 100f)
                val roll = ease(timeMillis, productRollStarts[index], productRollDurations[index])
                amount(canvas, index * 2, zeroFiat, productFiat[index], roll, 14f, 83f, 28f, panelText)
                amount(canvas, index * 2 + 1, zeroSats, productSats[index], roll, 14f, 107f, 12f, green)
            } else {
                rounded(canvas, 65f, 30f, 34f, 34f, 11f, iconSurface)
                line(canvas, 74f, 47f, 90f, 47f, panelText, 1.6f)
                line(canvas, 82f, 39f, 82f, 55f, panelText, 1.6f)
                label(canvas, addItemLabel, 82f, 91f, 13f, panelText, true, 136f, center = true)
            }
            canvas.restoreToCount(save)
        }
    }

    private fun drawTap(canvas: Canvas) {
        val screens = checkoutPreview ?: CheckoutPreviewScreens(context).also { checkoutPreview = it }
        val enter = ease(timeMillis, 0, 240)
        val charge = ease(timeMillis, 2600, 220)
        val paid = ease(timeMillis, 5930, 220)
        val keyStage = when {
            timeMillis < 1050 -> 0
            timeMillis < 1550 -> 1
            timeMillis < 2050 -> 2
            else -> 3
        }
        val save = canvas.saveLayerAlpha(0f, 0f, 360f, 380f, (enter * 255).toInt())
        // The terminal stays anchored; only the customer's handset moves to make the tap.
        handset(canvas, 180f, -8f, 398f) {
            canvas.drawColor(white)
            screenImage(canvas, screens.keypad[keyStage], 1f - charge)
            screenImage(canvas, screens.waiting, charge * (1f - paid))
            screenImage(canvas, screens.received, paid)
            if (charge < 1f) {
                val pressStart = when {
                    timeMillis in 900..1199 -> 900L
                    timeMillis in 1400..1699 -> 1400L
                    timeMillis in 1900..2199 -> 1900L
                    timeMillis in 2370..2650 -> 2370L
                    else -> -1L
                }
                if (pressStart >= 0) {
                    val key = when (pressStart) { 900L -> "3"; 1400L -> "5"; else -> "0" }
                    val isCharge = pressStart == 2370L
                    val target = if (isCharge) screens.chargeBounds else screens.keyBounds[key]
                    target?.let { keyPress(canvas, it, segment(timeMillis, pressStart, 280), isCharge) }
                }
            }
        }
        val arrival = segment(timeMillis, 3900, 720)
        val exitAcross = tapDeparture.getInterpolation(segment(timeMillis, 5660, 520))
        val exitLift = tapDeparture.getInterpolation(segment(timeMillis, 5660, 420))
        if (arrival > 0f && exitAcross < 1f) {
            // The two axes ease differently, so the straight segment becomes a placing arc:
            // a fast horizontal glide first, then a late, nearly vertical landing, finished
            // with a brief press-in dab — the contact the payment responds to.
            // Keep the shallow grip angle fixed instead of rotating the phone into contact.
            val across = tapAcross.getInterpolation(arrival) * (1f - exitAcross)
            val descend = tapDescend.getInterpolation(arrival) * (1f - exitLift)
            val press = sin(Math.PI * segment(timeMillis, 4620, 480)).toFloat()
            val cx = 464f - across * 202f
            val y = -64f + descend * 58f + press * 2.6f
            val customer = canvas.save()
            canvas.rotate(-5f, cx, y + 48f)
            customerHandset(canvas, cx, y, 366f)
            canvas.restoreToCount(customer)
        }
        canvas.restoreToCount(save)
    }

    private fun customerHandset(canvas: Canvas, centerX: Float, y: Float, height: Float) {
        val save = canvas.save()
        val scale = height / 1536f
        canvas.translate(centerX - 512f * scale, y)
        canvas.scale(scale, scale)
        paint.alpha = 255
        paint.isFilterBitmap = true
        canvas.drawBitmap(iphoneFrame, 0f, 0f, paint)
        val display = canvas.save()
        rect.set(219f, 113f, 804f, 1398f)
        path.reset()
        path.addRoundRect(rect, 78f, 78f, Path.Direction.CW)
        canvas.clipPath(path)
        // The supplied wallet screenshot is shown intact, including its native NFC scan sheet.
        canvas.drawBitmap(customerWallet, null, rect, paint)
        canvas.restoreToCount(display)
        val notch = canvas.save()
        path.reset()
        rect.set(369f, 88f, 654f, 158f)
        path.addRoundRect(rect, notchRadii, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawBitmap(iphoneFrame, 0f, 0f, paint)
        canvas.restoreToCount(notch)
        canvas.restoreToCount(save)
    }

    /** Native screen snapshots are composited into the measured display opening of the photo. */
    private inline fun handset(canvas: Canvas, centerX: Float, y: Float, height: Float, screen: () -> Unit) {
        val save = canvas.save()
        val scale = height / 1536f
        canvas.translate(centerX - 512f * scale, y)
        canvas.scale(scale, scale)
        paint.alpha = 255
        paint.isFilterBitmap = true
        canvas.drawBitmap(phoneFrame, 0f, 0f, paint)
        val display = canvas.save()
        path.reset()
        rect.set(215f, 109f, 809f, 1418f)
        path.addRoundRect(rect, 64f, 64f, Path.Direction.CW)
        canvas.clipPath(path)
        screen()
        canvas.restoreToCount(display)
        // Preserve the actual lens from the product render, above the native screen layer.
        val camera = canvas.save()
        path.reset()
        path.addCircle(512f, 141f, 18f, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawBitmap(phoneFrame, 0f, 0f, paint)
        canvas.restoreToCount(camera)
        canvas.restoreToCount(save)
    }

    private fun screenImage(canvas: Canvas, bitmap: Bitmap, alpha: Float) {
        if (alpha <= 0f) return
        paint.alpha = (alpha * 255).toInt()
        rect.set(215f, 180f, 809f, 1388f)
        canvas.drawBitmap(bitmap, null, rect, paint)
        paint.alpha = 255
    }

    private fun keyPress(canvas: Canvas, bounds: RectF, progress: Float, isCharge: Boolean) {
        val scaleX = 594f / CheckoutPreviewScreens.WIDTH
        val scaleY = 1208f / CheckoutPreviewScreens.HEIGHT
        // Keys sit on a white screen, so presses darken; the black charge button lightens.
        val opacity = sin(progress * Math.PI).toFloat() * if (isCharge) .30f else .10f
        paint.color = if (isCharge) white else Color.BLACK
        paint.alpha = (opacity * 255).toInt()
        rect.set(
            215f + bounds.left * scaleX, 180f + bounds.top * scaleY,
            215f + bounds.right * scaleX, 180f + bounds.bottom * scaleY,
        )
        canvas.drawRoundRect(rect, 28f, 28f, paint)
        paint.alpha = 255
    }

    private fun drawWithdrawals(canvas: Canvas) {
        var push = -1f
        for (index in notificationAmounts.indices) {
            push += spring(segment(timeMillis, notificationStarts[index], 650))
        }
        for (index in notificationAmounts.indices) {
            val progress = segment(timeMillis, notificationStarts[index], 650)
            if (progress <= 0f) continue
            val arrival = spring(progress)
            val depth = (push - index).coerceAtLeast(0f)
            if (depth >= 3f) continue
            val opacity = min(1f, progress * 5f) * (1f - depth / 3f)
            val scale = (1f - depth * .035f) * (.90f + arrival * .10f)
            val y = 80f + depth * 16f - (1f - arrival) * 55f
            val save = canvas.saveLayerAlpha(0f, 0f, 360f, 320f, (opacity * 255).toInt())
            canvas.translate(180f, y)
            val tilt = if (index % 2 == 0) -3.5f else 3.5f
            canvas.rotate(tilt * (1f - arrival), 0f, 43f)
            canvas.scale(scale, scale, 0f, 43f)
            rounded(canvas, -169f, 0f, 338f, 86f, 19f, surface)
            rounded(canvas, -153f, 22f, 42f, 42f, 13f, iconSurface)
            arrow(canvas, -140f, 48f, green)
            label(canvas, toWalletLabel, -98f, 36f, 13f, panelText, true, 126f)
            label(canvas, autoLabel, -98f, 57f, 10f, panelSecondary, maxWidth = 126f)
            label(canvas, notificationAmounts[index], 153f, 36f, 13f, panelText, right = true)
            label(canvas, sentLabel, 153f, 57f, 10f, green, right = true)
            canvas.restoreToCount(save)
        }
    }

    private fun drawSales(canvas: Canvas) {
        val enter = ease(timeMillis, 0, 700)
        val save = canvas.saveLayerAlpha(0f, 0f, 360f, 320f, (enter * 255).toInt())
        canvas.translate(0f, (1f - enter) * 14f)
        rounded(canvas, 9f, 19f, 342f, 282f, 23f, surface)
        outline(canvas, 9f, 19f, 342f, 282f, 23f, border)
        label(canvas, todayLabel, 30f, 51f, 13f, panelSecondary)
        val stage = if (timeMillis < 2700) 0 else 1
        val roll = ease(timeMillis, if (stage == 0) 1100 else 3000, 1150)
        amount(canvas, 0, salesFiat[stage], salesFiat[stage + 1], roll, 29f, 94f, 36f, panelText)
        amount(canvas, 1, salesSats[stage], salesSats[stage + 1], roll, 30f, 118f, 12f, green)
        val chartReveal = ease(timeMillis, 500, 1800)
        val morph = move(timeMillis, 2800, 1600)
        for (i in 0..2) line(canvas, 30f, 157f + i * 43f, 330f, 157f + i * 43f, border, .6f)
        path.reset()
        for (i in chartStart.indices) {
            val x = 30f + chartTimes[i] * 300f
            val value = chartStart[i] + (chartEnd[i] - chartStart[i]) * morph
            val y = 244f - value * 98f
            if (i == 0) path.moveTo(x, y)
            else {
                val prevX = 30f + chartTimes[i - 1] * 300f
                val previous = chartStart[i - 1] + (chartEnd[i - 1] - chartStart[i - 1]) * morph
                val prevY = 244f - previous * 98f
                val bend = (x - prevX) * .2f
                path.cubicTo(prevX + bend, prevY, x - bend, y, x, y)
            }
        }
        // The line draws itself along its own length, the dot riding the pen tip,
        // rather than a rectangular wipe uncovering a finished chart.
        if (chartReveal > 0f) {
            chartMeasure.setPath(path, false)
            val drawnLength = chartMeasure.length * chartReveal
            chartSegment.reset()
            chartMeasure.getSegment(0f, drawnLength, chartSegment, true)
            paint.color = green
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(chartSegment, paint)
            paint.style = Paint.Style.FILL
            chartMeasure.getPosTan(drawnLength, chartTip, null)
            canvas.drawCircle(chartTip[0], chartTip[1], 3.5f * min(1f, chartReveal / .06f), paint)
        }
        label(canvas, "09:00", 30f, 265f, 10f, panelSecondary)
        label(canvas, "12:00", 130f, 265f, 10f, panelSecondary, center = true)
        label(canvas, "18:00", 330f, 265f, 10f, panelSecondary, right = true)
        label(canvas, paymentCounts[if (roll > .5f) stage + 1 else stage], 330f, 51f, 11f, panelSecondary, right = true)
        canvas.restoreToCount(save)
    }

    private fun amount(
        canvas: Canvas,
        id: Int,
        from: String,
        to: String,
        progress: Float,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
    ) {
        textPaint(size, color, false)
        numberPainters[id].draw(canvas, from, to, progress, x, y, paint, decimalSeparator)
    }

    private fun productIcon(canvas: Canvas, index: Int, x: Float, y: Float) {
        val save = canvas.save()
        canvas.translate(x, y)
        paint.color = panelSecondary
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.strokeJoin = Paint.Join.ROUND
        when (index) {
            0 -> {
                rect.set(1f, 7f, 15f, 18f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                path.reset()
                path.moveTo(15f, 8f)
                path.cubicTo(23f, 6f, 23f, 17f, 15f, 15f)
                canvas.drawPath(path, paint)
                line(canvas, 0f, 21f, 18f, 21f, panelSecondary, 1.5f)
                line(canvas, 5f, 1f, 5f, 4f, panelSecondary, 1.5f)
                line(canvas, 10f, 0f, 10f, 3f, panelSecondary, 1.5f)
            }
            1 -> {
                path.reset()
                path.moveTo(6f, 1f)
                path.lineTo(0f, 5f)
                path.lineTo(3f, 11f)
                path.lineTo(6f, 9f)
                path.lineTo(6f, 22f)
                path.lineTo(18f, 22f)
                path.lineTo(18f, 9f)
                path.lineTo(21f, 11f)
                path.lineTo(24f, 5f)
                path.lineTo(18f, 1f)
                path.cubicTo(17f, 7f, 7f, 7f, 6f, 1f)
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> {
                rect.set(3f, 7f, 21f, 23f)
                canvas.drawRoundRect(rect, 2f, 2f, paint)
                path.reset()
                path.moveTo(8f, 10f)
                path.lineTo(8f, 5f)
                path.cubicTo(8f, -1f, 16f, -1f, 16f, 5f)
                path.lineTo(16f, 10f)
                canvas.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, color: Int) {
        line(canvas, x, y, x + 15f, y - 15f, color, 1.8f)
        line(canvas, x + 4f, y - 15f, x + 15f, y - 15f, color, 1.8f)
        line(canvas, x + 15f, y - 15f, x + 15f, y - 4f, color, 1.8f)
    }

    private fun label(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        maxWidth: Float = Float.MAX_VALUE,
        center: Boolean = false,
        right: Boolean = false,
    ) {
        textPaint(size, color, bold)
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
        paint.textAlign = when { center -> Paint.Align.CENTER; right -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        canvas.drawText(text, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = 255
        paint.textSize = size
        paint.typeface = if (bold) medium else regular
        paint.fontFeatureSettings = "tnum"
    }

    private fun rounded(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        rect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun outline(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        color: Int,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = color
        rect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun line(
        canvas: Canvas,
        x: Float,
        y: Float,
        endX: Float,
        endY: Float,
        color: Int,
        width: Float,
    ) {
        paint.color = color
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, y, endX, endY, paint)
    }

    private fun sats(value: Long) = context.getString(R.string.onboarding_tour_sats, integerFormat.format(value))
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    companion object {
        // Damping ratio ~0.66: one soft ~6% overshoot, calmer than a toy bounce.
        private fun spring(progress: Float): Float {
            if (progress <= 0f || progress >= 1f) return progress
            return 1f - exp(-11.5f * progress) *
                (cos(13f * progress) + (11.5f / 13f) * sin(13f * progress))
        }
        private val tapAcross = PathInterpolator(.16f, .84f, .28f, 1f)
        private val tapDescend = PathInterpolator(.6f, 0f, .15f, 1f)
        private val tapDeparture = PathInterpolator(.55f, 0f, .8f, .35f)
        private val easeInOut = PathInterpolator(.77f, 0f, .175f, 1f)
        private val easeOut = PathInterpolator(.23f, 1f, .32f, 1f)
        private fun segment(time: Long, delay: Long, duration: Long) =
            ((time - delay).toFloat() / duration).coerceIn(0f, 1f)
        private fun move(time: Long, delay: Long, duration: Long) =
            easeInOut.getInterpolation(segment(time, delay, duration))
        private fun ease(time: Long, delay: Long, duration: Long) =
            easeOut.getInterpolation(segment(time, delay, duration))
    }
}
