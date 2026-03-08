package com.electricdreams.numo.ui.components

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.google.android.material.card.MaterialCardView

/**
 * An animated, expandable card for adding new mints.
 * Features a collapsed state that expands to show URL input with QR scanning option.
 */
class AddMintInputCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnAddMintListener {
        fun onAddMint(mintUrl: String)
        fun onScanQR()
    }

    private val card: MaterialCardView
    private val headerContainer: View
    private val expandedContainer: View
    private val headerIcon: View
    private val headerTitle: TextView
    private val headerChevron: View
    private val helperText: TextView
    private val urlInput: EditText
    private val scanButton: ImageButton
    private val scanRowContainer: View
    private val scanRowTitle: TextView
    private val scanRowSubtitle: TextView
    private val addButton: TextView
    private val loadingIndicator: ProgressBar
    
    private var listener: OnAddMintListener? = null
    private var isExpanded = false
    private var onboardingModeEnabled = false
    private var useSecondaryAddButton = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.component_add_mint_input, this, true)
        
        card = findViewById(R.id.add_mint_inner_card)
        headerContainer = findViewById(R.id.header_container)
        expandedContainer = findViewById(R.id.expanded_container)
        headerIcon = findViewById(R.id.header_icon)
        headerTitle = findViewById(R.id.header_title)
        headerChevron = findViewById(R.id.header_chevron)
        helperText = findViewById(R.id.helper_text)
        urlInput = findViewById(R.id.url_input)
        scanButton = findViewById(R.id.scan_button)
        scanRowContainer = findViewById(R.id.scan_row_container)
        scanRowTitle = findViewById(R.id.scan_row_title)
        scanRowSubtitle = findViewById(R.id.scan_row_subtitle)
        addButton = findViewById(R.id.add_button)
        loadingIndicator = findViewById(R.id.loading_indicator)
        
        // Initial collapsed state
        expandedContainer.visibility = View.GONE
        expandedContainer.alpha = 0f
        applyPresentationMode()
        
        setupClickListeners()
        setupTextWatcher()
    }

    private fun setupClickListeners() {
        headerContainer.setOnClickListener {
            toggleExpanded()
        }
        
        scanButton.setOnClickListener { triggerScanAction(it) }
        scanRowContainer.setOnClickListener { triggerScanAction(it) }
        
        addButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                animateButtonPress(it)
                listener?.onAddMint(url)
            }
        }
    }

    private fun setupTextWatcher() {
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAddButtonState()
            }
        })
    }

    private fun updateAddButtonState() {
        val hasText = urlInput.text.toString().trim().isNotEmpty()
        addButton.isEnabled = hasText
        addButton.alpha = if (hasText) 1f else 0.5f
    }

    fun setOnAddMintListener(listener: OnAddMintListener) {
        this.listener = listener
    }

    fun setHeaderTitle(title: String) {
        headerTitle.text = title
    }

    fun setOnboardingModeEnabled(enabled: Boolean) {
        onboardingModeEnabled = enabled
        applyPresentationMode()
    }

    fun setUrlHint(hint: String) {
        urlInput.hint = hint
    }

    fun setScanRowText(title: String, subtitle: String) {
        scanRowTitle.text = title
        scanRowSubtitle.text = subtitle
    }

    fun setAddButtonStyleSecondary(enabled: Boolean) {
        useSecondaryAddButton = enabled
        applyAddButtonStyle()
    }

    fun setMintUrl(url: String) {
        urlInput.setText(url)
        if (!isExpanded) {
            expand()
        } else {
            // If already expanded, ensure the input is visible when setting URL programmatically
            scrollIntoView()
        }
    }

    fun clearInput() {
        urlInput.setText("")
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            addButton.visibility = View.INVISIBLE
            loadingIndicator.visibility = View.VISIBLE
            urlInput.isEnabled = false
            scanButton.isEnabled = false
            scanRowContainer.isEnabled = false
            scanRowContainer.alpha = 0.6f
        } else {
            addButton.visibility = View.VISIBLE
            loadingIndicator.visibility = View.GONE
            urlInput.isEnabled = true
            scanButton.isEnabled = true
            scanRowContainer.isEnabled = true
            scanRowContainer.alpha = 1f
        }
    }

    private fun triggerScanAction(view: View) {
        animateButtonPress(view)
        listener?.onScanQR()
    }

    private fun applyPresentationMode() {
        if (onboardingModeEnabled) {
            helperText.visibility = View.GONE
            scanButton.visibility = View.GONE
            scanRowContainer.visibility = View.VISIBLE
            setUrlHint(context.getString(R.string.onboarding_mints_add_hint))
            setScanRowText(
                context.getString(R.string.onboarding_mints_scan_row_title),
                context.getString(R.string.onboarding_mints_scan_row_subtitle),
            )
            useSecondaryAddButton = true
        } else {
            helperText.visibility = View.VISIBLE
            scanButton.visibility = View.VISIBLE
            scanRowContainer.visibility = View.GONE
            setUrlHint(context.getString(R.string.mints_url_hint))
            useSecondaryAddButton = false
        }
        applyAddButtonStyle()
    }

    private fun applyAddButtonStyle() {
        if (useSecondaryAddButton) {
            addButton.setBackgroundResource(R.drawable.bg_button_secondary_outlined)
            addButton.setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
            loadingIndicator.indeterminateTintList = ContextCompat.getColorStateList(
                context,
                R.color.color_text_primary
            )
        } else {
            addButton.setBackgroundResource(R.drawable.bg_button_primary_green)
            addButton.setTextColor(ContextCompat.getColor(context, R.color.color_bg_white))
            loadingIndicator.indeterminateTintList = ContextCompat.getColorStateList(
                context,
                android.R.color.white
            )
        }
    }

    private fun toggleExpanded() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }

    private fun expand() {
        isExpanded = true
        
        // Rotate chevron
        headerChevron.animate()
            .rotation(180f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        // Show expanded container
        expandedContainer.visibility = View.VISIBLE
        expandedContainer.alpha = 0f
        expandedContainer.translationY = -20f
        
        expandedContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        // Animate icon bounce
        headerIcon.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(150)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                headerIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
        
        // Focus input
        urlInput.postDelayed({
            urlInput.requestFocus()
            // Ensure the expanded card and keyboard are fully visible by scrolling
            scrollIntoView()
        }, 200)
    }

    private fun collapse() {
        isExpanded = false
        
        // Rotate chevron back
        headerChevron.animate()
            .rotation(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        // Hide expanded container
        expandedContainer.animate()
            .alpha(0f)
            .translationY(-20f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                expandedContainer.visibility = View.GONE
            }
            .start()
        
        // Clear focus and hide keyboard
        urlInput.clearFocus()
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(80)
                    .start()
            }
            .start()
    }

    /**
     * Smoothly scrolls the parent ScrollView (if any) so that this card is fully visible
     * above the keyboard. This makes the add‑mint experience feel polished on small screens.
     */
    private fun scrollIntoView() {
        val scrollView = findParentScrollView(this) ?: return

        scrollView.post {
            var targetTop = top
            var parent = parent
            while (parent is View && parent !== scrollView) {
                targetTop += parent.top
                parent = parent.parent
            }
            scrollView.smoothScrollTo(0, targetTop)
        }
    }

    private fun findParentScrollView(view: View): ScrollView? {
        var currentParent = view.parent
        while (currentParent is ViewGroup) {
            if (currentParent is ScrollView) return currentParent
            currentParent = currentParent.parent
        }
        return null
    }

    fun animateEntrance(delay: Long) {
        alpha = 0f
        translationY = 30f
        
        animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun collapseIfExpanded() {
        if (isExpanded) {
            collapse()
        }
    }
}
