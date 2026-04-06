package com.electricdreams.numo.ui.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentManager
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.DialogInputBinding
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.shake
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Material bottom sheet replacement for the input dialog.
 * Theme handles background color, corner radius, and dark mode.
 */
class InputBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogInputBinding? = null
    private val binding get() = _binding!!

    private var config: DialogHelper.InputConfig? = null

    companion object {
        private const val TAG = "InputBottomSheet"

        fun show(
            fragmentManager: FragmentManager,
            config: DialogHelper.InputConfig
        ): InputBottomSheet {
            return InputBottomSheet().apply {
                this.config = config
            }.also {
                it.show(fragmentManager, TAG)
            }
        }
    }

    override fun getTheme(): Int = R.style.Theme_Numo_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Allow keyboard to resize the bottom sheet
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        setupBottomSheetBehavior()
        bindConfig()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Populate the input field externally (e.g. after a QR scan).
     */
    fun populateInput(value: String) {
        _binding?.dialogInput?.let { editText ->
            editText.setText(value)
            editText.setSelection(editText.text.length)
        }
    }

    private fun bindConfig() {
        val cfg = config ?: return
        val ctx = requireContext()

        binding.dialogTitle.text = cfg.title

        cfg.description?.let { desc ->
            binding.dialogDescription.text = desc
            binding.dialogDescription.visibility = View.VISIBLE
        }

        binding.dialogInput.hint = cfg.hint
        binding.dialogInput.setText(cfg.initialValue)
        binding.dialogInput.inputType = cfg.inputType
        binding.dialogInput.setSelection(binding.dialogInput.text.length)

        cfg.prefix?.let { prefix ->
            binding.inputPrefix.text = prefix
            binding.inputPrefix.visibility = View.VISIBLE
            binding.dialogInput.setPadding(
                dpToPx(8), binding.dialogInput.paddingTop,
                binding.dialogInput.paddingRight, binding.dialogInput.paddingBottom
            )
        }

        cfg.suffix?.let { suffix ->
            binding.inputSuffix.text = suffix
            binding.inputSuffix.visibility = View.VISIBLE
            binding.dialogInput.setPadding(
                binding.dialogInput.paddingLeft, binding.dialogInput.paddingTop,
                dpToPx(8), binding.dialogInput.paddingBottom
            )
        }

        cfg.helperText?.let { helper ->
            binding.dialogHelper.text = helper
            binding.dialogHelper.visibility = View.VISIBLE
        }

        cfg.onScan?.let { onScan ->
            binding.inputScanButton.visibility = View.VISIBLE
            binding.inputScanButton.setOnClickListener {
                hideKeyboard()
                onScan.invoke()
            }
        }

        binding.saveButton.text = cfg.saveText

        binding.closeButton.setOnClickListener {
            hideKeyboard()
            dismiss()
            cfg.onCancel?.invoke()
        }

        binding.saveButton.setOnClickListener {
            val value = binding.dialogInput.text.toString()
            if (cfg.validator != null && !cfg.validator.invoke(value)) {
                binding.inputContainer.shake()
                return@setOnClickListener
            }
            hideKeyboard()
            dismiss()
            cfg.onSave(value)
        }

        binding.dialogInput.setOnEditorActionListener { _, _, _ ->
            binding.saveButton.performClick()
            true
        }

        // Request focus and show keyboard with slight delay for sheet animation
        binding.dialogInput.postDelayed({
            binding.dialogInput.requestFocus()
            ctx.getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.dialogInput, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun setupBottomSheetBehavior() {
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.isFitToContents = true
                behavior.skipCollapsed = true
                behavior.isDraggable = true

                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                            dismiss()
                        }
                    }
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // No-op
                    }
                })
            }
        }
    }

    private fun hideKeyboard() {
        val imm = context?.getSystemService<InputMethodManager>()
        _binding?.dialogInput?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
