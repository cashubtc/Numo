package com.electricdreams.numo.ui.seed

import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText optimized for entering single BIP39 seed words.
 *
 * This view intercepts multi-word paste operations (e.g. when a user pastes an
 * entire seed phrase into a single field) and forwards the raw pasted text to
 * [onSeedPasteListener] so the hosting screen can distribute the words across
 * multiple inputs.
 */
class SeedWordEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /**
     * Optional callback invoked when the user pastes multi-word text.
     *
     * Implementations should return `true` if the paste was handled and the
     * default insertion into this field should be suppressed, or `false` to
     * fall back to the standard paste behavior.
     */
    var onSeedPasteListener: ((String) -> Boolean)? = null

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Intercept standard paste actions so we can detect multi-word seed
        // phrases and hand them off to the hosting Activity.
        val isPaste = id == android.R.id.paste || id == android.R.id.pasteAsPlainText
        if (isPaste) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val pastedText = clip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()

            // Only delegate if this looks like multiple words (spaces or
            // line breaks). Single-word pastes are handled normally.
            if (pastedText.isNotEmpty() && pastedText.contains("\\s".toRegex())) {
                val handled = onSeedPasteListener?.invoke(pastedText) ?: false
                if (handled) {
                    // Hosting screen handled distribution & any user feedback;
                    // skip default insertion into this field.
                    return true
                }
            }
        }

        return super.onTextContextMenuItem(id)
    }
}
