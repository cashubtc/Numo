package com.electricdreams.numo.feature.onboarding

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

class AddMintBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onAddMintUrl(url: String)
        fun onScanQrCode()
    }

    private var listener: Listener? = null

    companion object {
        fun newInstance(listener: Listener): AddMintBottomSheet {
            return AddMintBottomSheet().apply {
                this.listener = listener
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            navigationBarColor = ContextCompat.getColor(requireContext(), R.color.numo_navy)
            setDimAmount(0.6f)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_add_mint, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val urlInput = view.findViewById<EditText>(R.id.mint_url_input)
        val addButton = view.findViewById<Button>(R.id.add_mint_button)
        val scanRow = view.findViewById<View>(R.id.scan_qr_button)

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = s?.toString()?.trim()?.isNotEmpty() == true
                addButton.isEnabled = hasText
            }
        })

        addButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                listener?.onAddMintUrl(url)
            }
        }

        scanRow.setOnClickListener {
            listener?.onScanQrCode()
        }

        // Focus immediately so the system includes the keyboard in the initial layout
        urlInput.requestFocus()

        setupBottomSheetBehavior()
    }

    fun setLoading(loading: Boolean) {
        val view = view ?: return
        val addButton = view.findViewById<Button>(R.id.add_mint_button)
        val loadingContainer = view.findViewById<LinearLayout>(R.id.add_mint_loading)
        val urlInput = view.findViewById<EditText>(R.id.mint_url_input)

        if (loading) {
            addButton.visibility = View.INVISIBLE
            loadingContainer.visibility = View.VISIBLE
            urlInput.isEnabled = false
        } else {
            addButton.visibility = View.VISIBLE
            loadingContainer.visibility = View.GONE
            urlInput.isEnabled = true
        }
    }

    private fun setupBottomSheetBehavior() {
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let { sheet ->
                // Apply background + rounded corners that were previously in the theme
                val density = resources.displayMetrics.density
                val shapeDrawable = MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setTopLeftCornerSize(20f * density)
                        .setTopRightCornerSize(20f * density)
                        .build()
                ).apply {
                    fillColor = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.numo_navy)
                    )
                }
                sheet.background = shapeDrawable

                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
    }
}
