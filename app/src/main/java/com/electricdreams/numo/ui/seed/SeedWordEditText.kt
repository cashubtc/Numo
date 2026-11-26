package com.electricdreams.numo.ui.seed

import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import java.util.Locale

/**
 * EditText optimized for entering single BIP39 seed words.
 *
 * This view intercepts multi-word paste operations (e.g. when a user pastes an
 * entire seed phrase into a single field) and forwards the raw pasted text to
 * [onSeedPasteListener] so the hosting screen can distribute the words across
 * multiple inputs.
 *
 * It also notifies [onWordCompleteListener] when the user finishes typing a
 * word, so the hosting screen can move focus to the next field.
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

    /**
     * Optional callback invoked when the user has typed a complete word: either
     * a valid word that no other BIP39 word starts with, or a valid word
     * followed by a space. Only fires for user input while focused, not for
     * programmatic [setText] calls such as paste distribution.
     */
    var onWordCompleteListener: (() -> Unit)? = null

    init {
        addTextChangedListener(object : TextWatcher {
            private var textGrew = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                textGrew = count > before
            }

            override fun afterTextChanged(s: Editable?) {
                if (!textGrew || s == null || !hasFocus()) return
                textGrew = false
                handleTypedText(s)
            }
        })
    }

    private fun handleTypedText(s: Editable) {
        val listener = onWordCompleteListener ?: return
        val raw = s.toString()
        val word = raw.trim().lowercase(Locale.ROOT)
        if (word.isEmpty() || word.any { it.isWhitespace() }) return

        val endsWithSpace = raw.last().isWhitespace()
        val complete = if (endsWithSpace) {
            Bip39Wordlist.isValid(word)
        } else {
            Bip39Wordlist.isUnambiguousWord(word)
        }
        if (!complete) return

        if (endsWithSpace) {
            // Drop the trailing space the user typed to confirm the word.
            s.replace(0, s.length, word)
        }
        post { listener() }
    }

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
