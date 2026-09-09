package com.electricdreams.numo.feature.onboarding

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityModernPosBinding
import com.electricdreams.numo.databinding.ActivityPaymentReceivedBinding
import com.electricdreams.numo.databinding.ActivityPaymentRequestBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.NumberFormat
import java.util.Currency

/**
 * Read-only previews made from the shipping layouts. No Activities, wallets, listeners, NFC,
 * or network are started. The preview QR encodes plain descriptive text, never a payment request.
 */
internal class CheckoutPreviewScreens(context: Context) {
    private val configuration = Configuration(context.resources.configuration).apply {
        densityDpi = DisplayMetrics.DENSITY_DEFAULT
        fontScale = 1f
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    private val previewContext = ContextThemeWrapper(
        context.createConfigurationContext(configuration), R.style.Theme_Numo,
    )
    private val inflater = LayoutInflater.from(previewContext)
    private val format = NumberFormat.getCurrencyInstance(configuration.locales[0]).apply {
        currency = Currency.getInstance("USD")
    }
    private val satFormat = NumberFormat.getIntegerInstance(configuration.locales[0])
    private val prices = OnboardingAmounts.checkoutCents
    val keyBounds = mutableMapOf<String, RectF>()
    lateinit var chargeBounds: RectF
        private set
    lateinit var amountBounds: RectF
        private set
    val keypad: List<Bitmap> = createKeypadScreens()
    val waiting: Bitmap = createWaitingScreen()
    val received: Bitmap = createReceivedScreen()

    private fun createKeypadScreens(): List<Bitmap> {
        val binding = ActivityModernPosBinding.inflate(inflater)
        // The shipping POS white theme, applied without an Activity or saved merchant preferences.
        val textColor = previewContext.getColor(R.color.color_theme_text_dark)
        binding.root.setBackgroundColor(previewContext.getColor(R.color.color_theme_white))
        binding.amountDisplay.setTextColor(textColor)
        // An unattached single-line TextView otherwise centers into a million-pixel scroll area.
        // Fixed preview prices fit the display, so no horizontal scrolling is needed.
        binding.amountDisplay.setHorizontallyScrolling(false)
        binding.secondaryAmountDisplay.setTextColor(textColor)
        listOf(binding.actionCatalog, binding.actionHistory, binding.actionSettings,
            binding.currencySwitchButton).forEach { it.setColorFilter(textColor) }
        binding.submitButton.setBackgroundResource(R.drawable.bg_button_black)
        binding.submitButton.setTextColor(Color.WHITE)
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "<")
        labels.forEach { label ->
            val key = inflater.inflate(R.layout.keypad_button_green_screen, binding.keypad, false) as Button
            key.text = label
            key.setTextColor(textColor)
            key.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 2, 4, 2)
            }
            binding.keypad.addView(key)
        }
        val images = prices.map { value ->
            binding.submitButton.isEnabled = value > 0
            binding.amountDisplay.text = format.format(value / 100.0)
            binding.secondaryAmountDisplay.text = previewContext.getString(
                R.string.onboarding_tour_sats, satFormat.format(OnboardingAmounts.sats(value)),
            )
            render(binding.root)
        }
        labels.forEachIndexed { index, label ->
            keyBounds[label] = boundsInRoot(binding.keypad.getChildAt(index), binding.root)
        }
        chargeBounds = boundsInRoot(binding.submitButton, binding.root)
        amountBounds = boundsInRoot(binding.amountDisplay, binding.root)
        return images
    }

    private fun createWaitingScreen(): Bitmap {
        val binding = ActivityPaymentRequestBinding.inflate(inflater)
        binding.largeAmountDisplay.text = format.format(3.5)
        binding.convertedAmountDisplay.apply {
            visibility = View.VISIBLE
            text = previewContext.getString(
                R.string.onboarding_tour_sats, satFormat.format(OnboardingAmounts.sats(prices.last())),
            )
        }
        binding.lightningQrContainer.visibility = View.GONE
        binding.cashuLoadingSpinner.visibility = View.GONE
        binding.cashuQrContainer.visibility = View.VISIBLE
        binding.cashuLogoCard.visibility = View.VISIBLE
        binding.paymentRequestQr.apply {
            visibility = View.VISIBLE
            setImageBitmap(previewQr())
        }
        binding.paymentStatusText.visibility = View.VISIBLE
        binding.nfcAnimationContainer.visibility = View.GONE
        return render(binding.root)
    }

    private fun createReceivedScreen(): Bitmap {
        val binding = ActivityPaymentReceivedBinding.inflate(inflater)
        binding.amountReceivedText.text = previewContext.getString(R.string.onboarding_tour_paid) +
            "\n" + format.format(3.5)
        binding.checkmarkCircle.visibility = View.VISIBLE
        binding.checkmarkIcon.visibility = View.VISIBLE
        // The shipping screen styles Close as a gray secondary, which reads as a disabled
        // primary at illustration scale; the tour presents it as the black next-sale action.
        binding.closeButton.setBackgroundResource(R.drawable.bg_button_black)
        binding.closeButton.setTextColor(Color.WHITE)
        return render(binding.root)
    }

    private fun render(view: View): Bitmap {
        // Text auto-sizing can request one follow-up layout before an offscreen snapshot.
        repeat(2) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, WIDTH, HEIGHT)
        }
        // Single-line TextViews finish their scroll/layout preparation in pre-draw.
        view.viewTreeObserver.dispatchOnPreDraw()
        return Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private fun previewQr(): Bitmap {
        val matrix = QRCodeWriter().encode(
            "Numo onboarding preview", BarcodeFormat.QR_CODE, 256, 256,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val pixels = IntArray(256 * 256) { index ->
            if (matrix[index % 256, index / 256]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
    }

    private fun boundsInRoot(view: View, root: View): RectF {
        var left = view.left.toFloat()
        var top = view.top.toFloat()
        var parent = view.parent
        while (parent is View && parent != root) {
            left += parent.left
            top += parent.top
            parent = parent.parent
        }
        return RectF(left, top, left + view.width, top + view.height)
    }

    companion object {
        const val WIDTH = 432
        const val HEIGHT = 880
    }
}
