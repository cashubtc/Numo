package com.electricdreams.numo.feature.pin

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.ui.util.DialogHelper
import com.google.android.material.button.MaterialButton

/**
 * Activity for resetting PIN using mnemonic verification.
 * 
 * Flow:
 * 1. User enters 12-word seed phrase
 * 2. Verify it matches stored mnemonic
 * 3. Remove PIN and optionally set new one
 */
class PinResetActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var seedInputGrid: GridLayout
    private lateinit var pasteButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var validationStatus: LinearLayout
    private lateinit var validationIcon: ImageView
    private lateinit var validationText: TextView

    private val seedInputs = mutableListOf<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_reset)

        pinManager = PinManager.getInstance(this)

        initViews()
        setupSeedInputs()
        setupListeners()
    }

    private fun initViews() {
        seedInputGrid = findViewById(R.id.seed_input_grid)
        pasteButton = findViewById(R.id.paste_button)
        resetButton = findViewById(R.id.reset_button)
        validationStatus = findViewById(R.id.validation_status)
        validationIcon = findViewById(R.id.validation_icon)
        validationText = findViewById(R.id.validation_text)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
    }

    private fun setupSeedInputs() {
        seedInputGrid.removeAllViews()
        seedInputs.clear()

        for (i in 0 until 12) {
            val inputContainer = createSeedInputView(i + 1)

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(i % 2, 1f)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            inputContainer.layoutParams = params

            seedInputGrid.addView(inputContainer)
        }
    }

    private fun createSeedInputView(index: Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_seed_input)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        val indexText = TextView(this).apply {
            text = "$index"
            setTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER
        }

        val input = EditText(this).apply {
            hint = ""
            setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.color_text_tertiary))
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            background = null
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = if (index == 12) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    validateInputs()
                }
            })

            setOnFocusChangeListener { _, hasFocus ->
                container.background = ContextCompat.getDrawable(
                    context,
                    if (hasFocus) R.drawable.bg_seed_input_focused else R.drawable.bg_seed_input
                )
            }
        }

        seedInputs.add(input)

        container.addView(indexText)
        container.addView(input)

        return container
    }

    private fun setupListeners() {
        pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        resetButton.setOnClickListener {
            showResetConfirmation()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(this, R.string.security_toast_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val pastedText = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
        val words = pastedText.split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (words.size != 12) {
            Toast.makeText(this, R.string.security_toast_seed_invalid, Toast.LENGTH_LONG).show()
            return
        }

        // Fill all inputs
        words.forEachIndexed { index, word ->
            if (index < seedInputs.size) {
                seedInputs[index].setText(word.lowercase())
            }
        }

        validateInputs()
        Toast.makeText(this, R.string.security_toast_seed_pasted, Toast.LENGTH_SHORT).show()
    }

    private fun validateInputs(): Boolean {
        val words = seedInputs.map { it.text.toString().trim().lowercase() }
        val filledCount = words.count { it.isNotBlank() }
        val allFilled = filledCount == 12
        val allValid = words.all { it.isBlank() || it.matches(Regex("^[a-z]+$")) }

        // Check if matches stored mnemonic
        val storedMnemonic = CashuWalletManager.getMnemonic()
        val enteredMnemonic = words.joinToString(" ")
        val matches = storedMnemonic != null && enteredMnemonic == storedMnemonic

        // Update validation status
        when {
            filledCount == 0 -> {
                validationStatus.visibility = View.GONE
            }
            !allValid -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_close)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning_red))
                validationText.text = "Invalid characters detected"
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning_red))
            }
            !allFilled -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_warning)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning))
                validationText.text = "$filledCount of 12 words entered"
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning))
            }
            !matches -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_close)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_warning_red))
                validationText.text = "Phrase doesn't match your wallet"
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_warning_red))
            }
            else -> {
                validationStatus.visibility = View.VISIBLE
                validationIcon.setImageResource(R.drawable.ic_check)
                validationIcon.setColorFilter(ContextCompat.getColor(this, R.color.color_success_green))
                validationText.text = "Recovery phrase verified"
                validationText.setTextColor(ContextCompat.getColor(this, R.color.color_success_green))
            }
        }

        // Update reset button state
        val canReset = allFilled && allValid && matches
        resetButton.isEnabled = canReset
        resetButton.alpha = if (canReset) 1f else 0.5f

        return canReset
    }

    private fun showResetConfirmation() {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = "Reset PIN?",
                message = "This will remove your current PIN. You can set a new one afterwards.",
                confirmText = "Reset",
                cancelText = "Cancel",
                isDestructive = true,
                onConfirm = { performReset() }
            )
        )
    }

    private fun performReset() {
        // Remove the PIN
        pinManager.removePin()
        pinManager.resetLockout()

        Toast.makeText(this, R.string.security_toast_pin_removed, Toast.LENGTH_SHORT).show()

        // Ask if user wants to set a new PIN
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = "Set New PIN?",
                message = "Would you like to set a new PIN now?",
                confirmText = "Set PIN",
                cancelText = "Not Now",
                isDestructive = false,
                onConfirm = {
                    val intent = Intent(this, PinSetupActivity::class.java)
                    intent.putExtra(PinSetupActivity.EXTRA_MODE, PinSetupActivity.MODE_CREATE)
                    startActivityForResult(intent, REQUEST_NEW_PIN)
                },
                onCancel = {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            )
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_NEW_PIN) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val REQUEST_NEW_PIN = 1001
    }
}
