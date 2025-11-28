package com.electricdreams.numo.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.gridlayout.widget.GridLayout
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.google.android.material.button.MaterialButton

/**
 * Activity for displaying the wallet's 12-word seed phrase.
 * Shows words in a numbered grid format with a single copy button.
 */
class SeedPhraseActivity : AppCompatActivity() {

    private lateinit var seedWordsGrid: GridLayout
    private lateinit var copyButton: MaterialButton
    private lateinit var toggleVisibilityButton: MaterialButton
    private var mnemonic: String? = null
    private var words: List<String> = emptyList()
    private var isRevealed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_seed_phrase)

        seedWordsGrid = findViewById(R.id.seed_words_grid)
        copyButton = findViewById(R.id.copy_button)
        toggleVisibilityButton = findViewById(R.id.toggle_visibility_button)

        findViewById<View>(R.id.back_button).setOnClickListener {
            finish()
        }

        // Load the mnemonic but keep it hidden initially
        loadMnemonic()
        displaySeedWords(blur = true)
        updateToggleButton()

        copyButton.setOnClickListener {
            if (!isRevealed) {
                Toast.makeText(
                    this,
                    "Tap Show to reveal your recovery phrase first",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                copyToClipboard()
            }
        }

        toggleVisibilityButton.setOnClickListener {
            isRevealed = !isRevealed
            displaySeedWords(blur = !isRevealed)
            updateToggleButton()
        }
    }

    private fun loadMnemonic() {
        mnemonic = CashuWalletManager.getMnemonic()

        if (mnemonic.isNullOrBlank()) {
            Toast.makeText(this, "Wallet not initialized", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        words = mnemonic!!.split(" ")
    }

    private fun displaySeedWords(blur: Boolean) {
        seedWordsGrid.removeAllViews()

        if (words.isEmpty()) return

        val inflater = LayoutInflater.from(this)

        words.forEachIndexed { index, word ->
            val wordView = inflater.inflate(R.layout.item_seed_word, seedWordsGrid, false)

            val indexText = wordView.findViewById<TextView>(R.id.word_index)
            val wordText = wordView.findViewById<TextView>(R.id.word_text)

            indexText.text = "${index + 1}"
            wordText.text = if (blur) "••••" else word

            // Set GridLayout params for 2-column layout
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % 2, 1f)
                rowSpec = GridLayout.spec(index / 2)
            }
            wordView.layoutParams = params

            seedWordsGrid.addView(wordView)
        }
    }

    private fun updateToggleButton() {
        if (isRevealed) {
            toggleVisibilityButton.text = "Hide"
            toggleVisibilityButton.setIconResource(R.drawable.ic_visibility_off)
        } else {
            toggleVisibilityButton.text = "Show"
            toggleVisibilityButton.setIconResource(R.drawable.ic_visibility)
        }
    }

    private fun copyToClipboard() {
        mnemonic?.let { phrase ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Seed Phrase", phrase)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Clear clipboard when leaving the activity for security
        // Note: This is optional and may be too aggressive for some users
    }
}
