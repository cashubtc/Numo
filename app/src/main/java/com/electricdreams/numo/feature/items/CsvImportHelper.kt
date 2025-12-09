package com.electricdreams.numo.feature.items

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.ItemManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper for importing items from a CSV file selected via the system picker.
 *
 * This centralizes the CSV → temporary file copy, calls into [ItemManager.importItemsFromCsv],
 * and provides consistent user feedback (toasts) for success, empty imports, and errors.
 */
object CsvImportHelper {

    private const val TAG = "CsvImportHelper"

    /**
     * Import items from a CSV [uri] into the catalog.
     *
     * @param context        Activity or application context used for IO and toasts.
     * @param itemManager    The [ItemManager] used to persist imported items.
     * @param uri            The URI returned by the system file picker.
     * @param clearExisting  If true, existing items are cleared before import.
     * @param onItemsImported Callback invoked when items were successfully imported
     *                        (i.e. imported count > 0). The imported count is passed in.
     */
    fun importItemsFromCsvUri(
        context: Context,
        itemManager: ItemManager,
        uri: Uri,
        clearExisting: Boolean,
        onItemsImported: (importedCount: Int) -> Unit
    ) {
        try {
            val tempFile = File(context.cacheDir, "import_catalog.csv")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    copyStream(inputStream, outputStream)
                }
            } ?: run {
                Toast.makeText(
                    context,
                    context.getString(R.string.item_list_toast_failed_open_csv),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val importedCount = itemManager.importItemsFromCsv(
                tempFile.absolutePath,
                clearExisting
            )

            if (importedCount > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.item_list_toast_imported_items, importedCount),
                    Toast.LENGTH_SHORT
                ).show()
                onItemsImported(importedCount)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.item_list_toast_no_items_imported),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error importing CSV file: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.item_list_toast_error_importing_csv, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyStream(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(4096)
        var bytesRead: Int
        while (true) {
            bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break
            outputStream.write(buffer, 0, bytesRead)
        }
    }
}
