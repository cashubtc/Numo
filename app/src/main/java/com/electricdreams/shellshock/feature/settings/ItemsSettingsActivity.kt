package com.electricdreams.shellshock.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.R
import com.electricdreams.shellshock.core.util.ItemManager
import com.electricdreams.shellshock.feature.items.ItemListActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ItemsSettingsActivity : AppCompatActivity() {

    private lateinit var itemsStatusText: TextView
    private lateinit var addItemsButton: Button
    private lateinit var importItemsButton: Button
    private lateinit var clearItemsButton: View

    private val csvPickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                importCsvFile(uri)
            }
        }

    private val itemListLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                updateItemsStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_items_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        itemsStatusText = findViewById(R.id.items_status_text)
        addItemsButton = findViewById(R.id.add_items_button)
        importItemsButton = findViewById(R.id.import_items_button)
        clearItemsButton = findViewById(R.id.clear_items_button)

        updateItemsStatus()

        addItemsButton.setOnClickListener {
            val intent = Intent(this, ItemListActivity::class.java)
            itemListLauncher.launch(intent)
        }

        importItemsButton.setOnClickListener {
            csvPickerLauncher.launch("text/csv")
        }

        clearItemsButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Items")
                .setMessage("Are you sure you want to delete ALL items from your catalog? This cannot be undone.")
                .setPositiveButton("Delete All Items") { _, _ ->
                    val itemManager = ItemManager.getInstance(this)
                    itemManager.clearItems()
                    updateItemsStatus()
                    Toast.makeText(this, "All items cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateItemsStatus()
    }

    private fun updateItemsStatus() {
        val itemManager = ItemManager.getInstance(this)
        val itemCount = itemManager.getAllItems().size

        itemsStatusText.text = if (itemCount == 0) {
            "No items in catalog"
        } else {
            "$itemCount items in catalog"
        }
    }

    private fun importCsvFile(uri: Uri) {
        try {
            val tempFile = File(cacheDir, "import_catalog.csv")

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    copyStream(inputStream, outputStream)
                }
            } ?: run {
                Toast.makeText(this, "Failed to open CSV file", Toast.LENGTH_SHORT).show()
                return
            }

            val itemManager = ItemManager.getInstance(this)
            val importedCount = itemManager.importItemsFromCsv(tempFile.absolutePath, true)

            if (importedCount > 0) {
                Toast.makeText(this, "Imported $importedCount items", Toast.LENGTH_SHORT).show()
                updateItemsStatus()
            } else {
                Toast.makeText(this, "No items imported from CSV", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error importing CSV file: ${e.message}", e)
            Toast.makeText(this, "Error importing CSV file: ${e.message}", Toast.LENGTH_SHORT).show()
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

    companion object {
        private const val TAG = "ItemsSettingsActivity"
    }
}
