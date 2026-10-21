package com.electricdreams.numo.ui.components

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.DialogUnitPickerHeaderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A themed, accessible single-choice dialog shared by charge and mint unit selectors. */
object UnitPickerDialog {
    fun show(
        context: Context,
        @StringRes title: Int,
        labels: List<String>,
        selectedIndex: Int = -1,
        @StringRes description: Int? = null,
        onSelected: (Int) -> Unit,
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context).setTitle(title)
        if (description != null) {
            val header = DialogUnitPickerHeaderBinding.inflate(LayoutInflater.from(builder.context))
            header.unitPickerTitle.setText(title)
            ViewCompat.setAccessibilityHeading(header.unitPickerTitle, true)
            header.unitPickerDescription.setText(description)
            // AlertDialog's standard message replaces its choice list, so use a header.
            builder.setCustomTitle(header.root)
        }
        return builder
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, index ->
                dialog.dismiss()
                onSelected(index)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
