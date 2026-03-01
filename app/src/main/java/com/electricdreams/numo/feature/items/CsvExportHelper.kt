package com.electricdreams.numo.feature.items

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.ItemManager

/**
 * Helper for exporting items to a CSV file selected via the system picker.
 */
object CsvExportHelper {

    private const val TAG = "CsvExportHelper"

    /**
     * Export items to a CSV [uri].
     *
     * @param context        Activity or application context used for IO and toasts.
     * @param itemManager    The [ItemManager] used to fetch existing items.
     * @param uri            The URI returned by the system file picker (CreateDocument).
     */
    fun exportItemsToCsvUri(
        context: Context,
        itemManager: ItemManager,
        uri: Uri
    ) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.item_list_toast_error_exporting),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            
            outputStream.use { stream ->
                val success = itemManager.exportItemsToCsv(stream)
                if (success) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.item_list_toast_exported),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.item_list_toast_error_exporting),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting CSV file: \${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.item_list_toast_error_exporting),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
