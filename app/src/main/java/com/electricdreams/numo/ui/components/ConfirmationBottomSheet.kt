package com.electricdreams.numo.ui.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.DialogConfirmationBinding
import com.electricdreams.numo.ui.util.DialogHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Material bottom sheet replacement for the confirmation dialog.
 * Theme handles background color, corner radius, and dark mode.
 */
class ConfirmationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogConfirmationBinding? = null
    private val binding get() = _binding!!

    private var config: DialogHelper.ConfirmationConfig? = null

    companion object {
        private const val TAG = "ConfirmationBottomSheet"

        private val appleSpring = PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)

        fun show(
            fragmentManager: FragmentManager,
            config: DialogHelper.ConfirmationConfig
        ): ConfirmationBottomSheet {
            return ConfirmationBottomSheet().apply {
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
        _binding = DialogConfirmationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBottomSheetBehavior()
        bindConfig()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindConfig() {
        val cfg = config ?: return

        binding.dialogTitle.text = cfg.title
        binding.dialogMessage.text = cfg.message
        binding.confirmButton.text = cfg.confirmText

        // Optional icon
        cfg.icon?.let { iconRes ->
            binding.dialogIcon.setImageResource(iconRes)
            binding.dialogIcon.visibility = View.VISIBLE
            animateIcon(binding.dialogIcon)
        }

        // Optional subtitle
        cfg.subtitle?.let { subtitle ->
            binding.dialogSubtitle.text = subtitle
            binding.dialogSubtitle.visibility = View.VISIBLE
        }

        if (cfg.isDestructive) {
            binding.confirmButton.setBackgroundResource(R.drawable.bg_button_destructive)
        }

        binding.closeButton.setOnClickListener {
            dismiss()
            cfg.onCancel?.invoke()
        }

        binding.confirmButton.setOnClickListener {
            dismiss()
            cfg.onConfirm()
        }
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

    /**
     * Icon entrance: gentle scale + fade with delay.
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
}
