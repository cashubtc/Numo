package com.electricdreams.numo.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.electricdreams.numo.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A themed, accessible single-choice dialog shared by charge and mint unit selectors. */
object UnitPickerDialog {
    fun show(
        context: Context,
        @StringRes title: Int,
        labels: List<String>,
        selectedIndex: Int = -1,
        onSelected: (Int) -> Unit,
    ): AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, index ->
            dialog.dismiss()
            onSelected(index)
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}
