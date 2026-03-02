package com.electricdreams.numo.ui.util

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R

/**
 * Premium dialog helper for consistent, Apple/Google-style dialogs across the app.
 * 
 * Features:
 * - Confirmation dialogs with optional destructive styling
 * - Input dialogs with prefix/suffix support
 * - Smooth entrance animations
 * - Consistent rounded corners and shadows
 */
object DialogHelper {
    
    /**
     * Configuration for confirmation dialogs
     */
    data class ConfirmationConfig(
        val title: String,
        val message: String,
        val confirmText: String = "Confirm",
        val cancelText: String = "Cancel",
        val isDestructive: Boolean = false,
        val onConfirm: () -> Unit,
        val onCancel: (() -> Unit)? = null
    )
    
    /**
     * Configuration for input dialogs
     */
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
    
    /**
     * Show a confirmation dialog with consistent Apple/Google styling.
     */
    fun showConfirmation(context: Context, config: ConfirmationConfig): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirmation, null)
        
        // Setup views
        val closeButton = dialogView.findViewById<ImageButton>(R.id.close_button)
        val titleText = dialogView.findViewById<TextView>(R.id.dialog_title)
        val messageText = dialogView.findViewById<TextView>(R.id.dialog_message)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancel_button)
        val confirmButton = dialogView.findViewById<Button>(R.id.confirm_button)
        
        // Configure content
        titleText.text = config.title
        messageText.text = config.message
        cancelButton.text = config.cancelText
        confirmButton.text = config.confirmText
        
        // Destructive styling (solid red background)
        if (config.isDestructive) {
            confirmButton.background = ContextCompat.getDrawable(context, R.drawable.bg_button_destructive)
        }
        
        // Create dialog
        val dialog = AlertDialog.Builder(context, R.style.Theme_Numo_Dialog)
            .setView(dialogView)
            .create()
        
        // Set window properties for floating dialog
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Click handlers
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
        
        return dialog
    }
    
    /**
     * Show an input dialog with consistent Apple/Google styling.
     */
    fun showInput(context: Context, config: InputConfig): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        
        // Setup views
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
        
        // Configure content
        titleText.text = config.title
        
        if (config.description != null) {
            descriptionText.text = config.description
            descriptionText.visibility = View.VISIBLE
        } else {
            descriptionText.visibility = View.GONE
            // Reduce top margin of input container when no description
            val params = inputContainer.layoutParams as? LinearLayout.LayoutParams
            params?.topMargin = 0
        }
        
        inputField.hint = config.hint
        inputField.setText(config.initialValue)
        inputField.inputType = config.inputType
        inputField.setSelection(inputField.text.length)
        
        if (config.prefix != null) {
            prefixText.text = config.prefix
            prefixText.visibility = View.VISIBLE
            // Reduce start padding of input since prefix has padding
            inputField.setPadding(
                dpToPx(context, 8),
                inputField.paddingTop,
                inputField.paddingRight,
                inputField.paddingBottom
            )
        }
        
        if (config.suffix != null) {
            suffixText.text = config.suffix
            suffixText.visibility = View.VISIBLE
            // Reduce end padding of input since suffix has padding
            inputField.setPadding(
                inputField.paddingLeft,
                inputField.paddingTop,
                dpToPx(context, 8),
                inputField.paddingBottom
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
        } else {
            scanButton.visibility = View.GONE
        }
        
        saveButton.text = config.saveText
        
        // Create dialog
        val dialog = AlertDialog.Builder(context, R.style.Theme_Numo_Dialog)
            .setView(dialogView)
            .create()
        
        // Set window properties for floating dialog
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        
        // Click handlers
        closeButton.setOnClickListener { 
            hideKeyboard(context, inputField)
            animateDismiss(dialogView, dialog)
            config.onCancel?.invoke()
        }
        
        saveButton.setOnClickListener {
            val value = inputField.text.toString()
            
            // Validate if validator provided
            if (config.validator != null && !config.validator.invoke(value)) {
                // Shake animation for invalid input
                animateShake(inputContainer)
                return@setOnClickListener
            }
            
            hideKeyboard(context, inputField)
            animateDismiss(dialogView, dialog)
            config.onSave(value)
        }
        
        // Handle IME action
        inputField.setOnEditorActionListener { _, _, _ ->
            saveButton.performClick()
            true
        }
        
        dialog.show()
        animateEntrance(dialogView)
        
        // Show keyboard after a short delay
        inputField.postDelayed({
            inputField.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
        
        return dialog
    }
    
    /**
     * Animate dialog entrance with scale + fade
     */
    private fun animateEntrance(view: View) {
        view.alpha = 0f
        view.scaleX = 0.9f
        view.scaleY = 0.9f
        
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }
    
    /**
     * Animate dialog dismissal
     */
    private fun animateDismiss(view: View, dialog: AlertDialog) {
        view.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(150)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                dialog.dismiss()
            }
            .start()
    }
    
    /**
     * Shake animation for invalid input
     */
    private fun animateShake(view: View) {
        val shake = android.view.animation.TranslateAnimation(-10f, 10f, 0f, 0f).apply {
            duration = 50
            repeatCount = 5
            repeatMode = android.view.animation.Animation.REVERSE
        }
        view.startAnimation(shake)
    }
    
    /**
     * Hide keyboard
     */
    private fun hideKeyboard(context: Context, view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }
    
    /**
     * Convert dp to pixels
     */
    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
