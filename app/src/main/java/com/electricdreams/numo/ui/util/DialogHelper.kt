package com.electricdreams.numo.ui.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R

/**
 * Bottom-floating dialog helper for consistent styling across the app.
 *
 * Dialogs slide up from the bottom with Jakub Krehel-style materializing
 * animation (translateY + alpha + blur) and dismiss with a subtler exit.
 */
object DialogHelper {

    private val appleSpring = PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)
    private val easeOut = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)

    data class ConfirmationConfig(
        val title: String,
        val message: String,
        val subtitle: String? = null,
        val icon: Int? = null,
        val confirmText: String = "Confirm",
        val cancelText: String = "Cancel",
        val isDestructive: Boolean = false,
        val showCancelButton: Boolean = true,
        val onConfirm: () -> Unit,
        val onCancel: (() -> Unit)? = null
    )

    data class InputConfig(
        val title: String,
        val description: String? = null,
        val hint: String = "",
        val initialValue: String = "",
        val prefix: String? = null,
        val suffix: String? = null,
        val helperText: String? = null,
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
        val saveText: String = "Save",
        val onSave: (String) -> Unit,
        val onCancel: (() -> Unit)? = null,
        val validator: ((String) -> Boolean)? = null,
        val onScan: (() -> Unit)? = null
    )

    fun showConfirmation(context: Context, config: ConfirmationConfig): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirmation, null)

        val closeButton = dialogView.findViewById<ImageButton>(R.id.close_button)
        val iconView = dialogView.findViewById<ImageView>(R.id.dialog_icon)
        val titleText = dialogView.findViewById<TextView>(R.id.dialog_title)
        val subtitleText = dialogView.findViewById<TextView>(R.id.dialog_subtitle)
        val messageText = dialogView.findViewById<TextView>(R.id.dialog_message)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancel_button)
        val confirmButton = dialogView.findViewById<Button>(R.id.confirm_button)

        titleText.text = config.title
        messageText.text = config.message
        cancelButton.text = config.cancelText
        confirmButton.text = config.confirmText

        // Optional icon
        if (config.icon != null) {
            iconView.setImageResource(config.icon)
            iconView.visibility = View.VISIBLE
        }

        // Optional subtitle
        if (config.subtitle != null) {
            subtitleText.text = config.subtitle
            subtitleText.visibility = View.VISIBLE
        }

        if (config.isDestructive) {
            confirmButton.background = ContextCompat.getDrawable(context, R.drawable.bg_button_destructive)
        }

        if (!config.showCancelButton) {
            cancelButton.visibility = View.GONE
            (confirmButton.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_Numo_BottomFloatingDialog)
            .setView(dialogView)
            .create()

        setupBottomSheetWindow(context, dialog)

        closeButton.setOnClickListener {
            animateDismiss(dialogView, dialog)
            config.onCancel?.invoke()
        }

        cancelButton.setOnClickListener {
            animateDismiss(dialogView, dialog)
            config.onCancel?.invoke()
        }

        confirmButton.setOnClickListener {
            animateDismiss(dialogView, dialog)
            config.onConfirm()
        }

        dialog.show()
        animateEntrance(dialogView)

        // Animate icon with delay if present
        if (config.icon != null) {
            animateIcon(iconView)
        }

        return dialog
    }

    fun showInput(context: Context, config: InputConfig): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)

        val closeButton = dialogView.findViewById<ImageButton>(R.id.close_button)
        val titleText = dialogView.findViewById<TextView>(R.id.dialog_title)
        val descriptionText = dialogView.findViewById<TextView>(R.id.dialog_description)
        val inputContainer = dialogView.findViewById<LinearLayout>(R.id.input_container)
        val prefixText = dialogView.findViewById<TextView>(R.id.input_prefix)
        val inputField = dialogView.findViewById<EditText>(R.id.dialog_input)
        val suffixText = dialogView.findViewById<TextView>(R.id.input_suffix)
        val helperText = dialogView.findViewById<TextView>(R.id.dialog_helper)
        val scanButton = dialogView.findViewById<ImageButton>(R.id.input_scan_button)
        val saveButton = dialogView.findViewById<Button>(R.id.save_button)

        titleText.text = config.title

        if (config.description != null) {
            descriptionText.text = config.description
            descriptionText.visibility = View.VISIBLE
        }

        inputField.hint = config.hint
        inputField.setText(config.initialValue)
        inputField.inputType = config.inputType
        inputField.setSelection(inputField.text.length)

        if (config.prefix != null) {
            prefixText.text = config.prefix
            prefixText.visibility = View.VISIBLE
            inputField.setPadding(
                dpToPx(context, 8), inputField.paddingTop,
                inputField.paddingRight, inputField.paddingBottom
            )
        }

        if (config.suffix != null) {
            suffixText.text = config.suffix
            suffixText.visibility = View.VISIBLE
            inputField.setPadding(
                inputField.paddingLeft, inputField.paddingTop,
                dpToPx(context, 8), inputField.paddingBottom
            )
        }

        if (config.helperText != null) {
            helperText.text = config.helperText
            helperText.visibility = View.VISIBLE
        }

        if (config.onScan != null) {
            scanButton.visibility = View.VISIBLE
            scanButton.setOnClickListener {
                hideKeyboard(context, inputField)
                config.onScan.invoke()
            }
        }

        saveButton.text = config.saveText

        val dialog = AlertDialog.Builder(context, R.style.Theme_Numo_BottomFloatingDialog)
            .setView(dialogView)
            .create()

        setupBottomSheetWindow(context, dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        closeButton.setOnClickListener {
            hideKeyboard(context, inputField)
            animateDismiss(dialogView, dialog)
            config.onCancel?.invoke()
        }

        saveButton.setOnClickListener {
            val value = inputField.text.toString()
            if (config.validator != null && !config.validator.invoke(value)) {
                animateShake(inputContainer)
                return@setOnClickListener
            }
            hideKeyboard(context, inputField)
            animateDismiss(dialogView, dialog)
            config.onSave(value)
        }

        inputField.setOnEditorActionListener { _, _, _ ->
            saveButton.performClick()
            true
        }

        dialog.show()
        animateEntrance(dialogView)

        inputField.postDelayed({
            inputField.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 300)

        return dialog
    }

    /**
     * Configure dialog window for bottom-floating sheet positioning.
     */
    private fun setupBottomSheetWindow(context: Context, dialog: AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            // Horizontal + bottom margin for floating effect
            val margin = dpToPx(context, 16)
            attributes = attributes?.apply {
                horizontalMargin = margin.toFloat() / context.resources.displayMetrics.widthPixels
            }
            decorView.setPadding(0, 0, 0, margin)
        }
    }

    /**
     * Entrance: slide up + fade in + blur (Jakub Krehel materializing effect)
     */
    private fun animateEntrance(view: View) {
        val density = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = 60f * density

        val maxBlur = 4f * density

        // Animated blur: materializing from blurred to sharp (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(maxBlur, maxBlur, Shader.TileMode.CLAMP)
            )

            ValueAnimator.ofFloat(maxBlur, 0f).apply {
                duration = 350
                interpolator = appleSpring
                addUpdateListener { animator ->
                    val radius = animator.animatedValue as Float
                    if (radius > 0.01f) {
                        view.setRenderEffect(
                            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        )
                    } else {
                        view.setRenderEffect(null)
                    }
                }
                start()
            }
        }

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(appleSpring)
            .start()
    }

    /**
     * Exit: slide down + fade out (subtler than entrance per Jakub's rule)
     */
    private fun animateDismiss(view: View, dialog: AlertDialog) {
        val density = view.resources.displayMetrics.density
        view.animate()
            .alpha(0f)
            .translationY(40f * density)
            .setDuration(200)
            .setInterpolator(easeOut)
            .withEndAction {
                dialog.dismiss()
            }
            .start()
    }

    /**
     * Icon entrance: gentle scale + fade with delay
     */
    private fun animateIcon(iconView: View) {
        iconView.alpha = 0f
        iconView.scaleX = 0.9f
        iconView.scaleY = 0.9f
        iconView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setStartDelay(100)
            .setInterpolator(appleSpring)
            .start()
    }

    private fun animateShake(view: View) {
        view.shake(amplitude = 10f)
    }

    private fun hideKeyboard(context: Context, view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
