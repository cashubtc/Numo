package com.electricdreams.numo.ui.util

import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.ui.components.ConfirmationBottomSheet
import com.electricdreams.numo.ui.components.InputBottomSheet

/**
 * Bottom sheet dialog helper for consistent styling across the app.
 *
 * Delegates to [ConfirmationBottomSheet] and [InputBottomSheet] which use
 * the Material BottomSheetDialogFragment with Theme_Numo_BottomSheet.
 */
object DialogHelper {

    data class ConfirmationConfig(
        val title: String,
        val message: String,
        val subtitle: String? = null,
        val icon: Int? = null,
        val confirmText: String = "Confirm",
        val isDestructive: Boolean = false,
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

    fun showConfirmation(
        context: AppCompatActivity,
        config: ConfirmationConfig
    ): ConfirmationBottomSheet {
        return ConfirmationBottomSheet.show(context.supportFragmentManager, config)
    }

    fun showInput(
        context: AppCompatActivity,
        config: InputConfig
    ): InputBottomSheet {
        return InputBottomSheet.show(context.supportFragmentManager, config)
    }
}
